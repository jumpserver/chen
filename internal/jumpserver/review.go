package jumpserver

import (
	"context"
	"errors"
	"net/url"
	"strings"
	"unicode/utf8"

	"github.com/jumpserver/wisp/protobuf-go/protobuf"
	"google.golang.org/protobuf/proto"
)

// Core's CommandFilterACL.create_command_review_ticket stores only the first
// 4090 Unicode characters. Reject overlong batches instead of approving a prefix
// and then executing an unreviewed suffix.
const MaxReviewCharacters = 4090

func (p *WispProvider) AuthorizeCommand(ctx context.Context, g *Grant, request AuthorizationRequest) (AuthorizationDecision, error) {
	decision := AuthorizationDecision{}
	err := p.Authorize(ctx, g, request.SQL)
	if g == nil {
		return decision, err
	}
	matched := matchPolicy(g, request.SQL)
	if matched != nil {
		decision.ACLID, decision.GroupID = matched.aclID, matched.groupID
		switch matched.action {
		case protobuf.CommandACL_Warning, protobuf.CommandACL_NotifyWarning:
			decision.RiskLevel = int32(protobuf.RiskLevel_Warning)
		case protobuf.CommandACL_Reject, protobuf.CommandACL_Review:
			var policy *PolicyError
			if errors.As(err, &policy) {
				decision.RiskLevel = policy.RiskLevel
			}
		}
	}
	var policy *PolicyError
	if errors.As(err, &policy) && policy.Code == "command_warning_confirmation_required" {
		decision.NotifyWarning = true
		if request.ConfirmWarnings {
			return decision, nil
		}
		return decision, err
	}
	if !errors.As(err, &policy) || policy.Code != "command_review_required" {
		return decision, err
	}
	batch := request.BatchSQL
	if batch == "" {
		batch = request.SQL
	}
	if !utf8.ValidString(batch) || utf8.RuneCountInString(batch) > MaxReviewCharacters {
		return decision, decision.policyError("command_review_sql_too_long")
	}
	if !request.SubmitReview {
		return decision, err
	}
	return p.waitCommandReview(ctx, g, decision, batch, request.OnReview)
}

func (d AuthorizationDecision) policyError(code string) error {
	return &PolicyError{Code: code, RiskLevel: d.RiskLevel, ACLID: d.ACLID, GroupID: d.GroupID}
}

func (p *WispProvider) waitCommandReview(parent context.Context, g *Grant, decision AuthorizationDecision, batch string, callback func(ReviewStatus)) (result AuthorizationDecision, resultErr error) {
	ctx, cancel := context.WithTimeout(parent, p.options.ReviewTimeout)
	defer cancel()
	expiresAt, _ := ctx.Deadline()
	status := ReviewStatus{State: "pending", ExpiresAt: expiresAt}
	notify := func() {
		if callback != nil {
			copy := status
			copy.Reviewers = append([]string(nil), status.Reviewers...)
			callback(copy)
		}
	}
	finish := func(state, code string, risk protobuf.RiskLevel) (AuthorizationDecision, error) {
		status.State, status.ErrorCode = state, code
		decision.RiskLevel = int32(risk)
		notify()
		if code == "" {
			return decision, nil
		}
		return decision, decision.policyError(code)
	}
	contextFailure := func() (AuthorizationDecision, error) {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return finish("timed_out", "command_review_timed_out", protobuf.RiskLevel_ReviewCancel)
		}
		return finish("canceled", "command_review_canceled", protobuf.RiskLevel_ReviewCancel)
	}
	info, err := p.createReviewTicket(ctx, &protobuf.CommandConfirmRequest{SessionId: g.ID, CmdAclId: decision.ACLID, Cmd: batch})
	if err != nil {
		if ctx.Err() != nil {
			return contextFailure()
		}
		return finish("failed", "command_review_failed", protobuf.RiskLevel_ReviewReject)
	}
	terminal := false
	defer func() {
		if !terminal {
			if err := p.enqueueTicketCancel(info.CancelReq); err != nil {
				resultErr = errors.Join(resultErr, err)
			}
		}
	}()
	status.TicketURL = safeTicketURL(info.TicketDetailUrl)
	status.Reviewers = append([]string(nil), info.Reviewers...)
	notify()
	for {
		if ctx.Err() != nil {
			return contextFailure()
		}
		if err := g.Check(); err != nil {
			return finish("canceled", err.Error(), protobuf.RiskLevel_ReviewCancel)
		}
		requestCtx, requestCancel := p.rpcContext(ctx)
		response, err := p.client.CheckTicketState(requestCtx, &protobuf.TicketRequest{Req: info.CheckReq})
		requestCancel()
		if ctx.Err() != nil {
			return contextFailure()
		}
		if err != nil || response == nil || !statusOK(response.Status) || response.Data == nil {
			return finish("failed", "command_review_failed", protobuf.RiskLevel_ReviewReject)
		}
		status.Processor = response.Data.Processor
		switch response.Data.State {
		case protobuf.TicketState_Open:
			if !waitContext(ctx, p.options.ReviewPollInterval) {
				return contextFailure()
			}
		case protobuf.TicketState_Approved:
			terminal = true
			// An approval arriving concurrently with lock/revocation/expiry is
			// not permission to execute. The engine checks again before sending SQL.
			if err := g.Check(); err != nil {
				return finish("canceled", err.Error(), protobuf.RiskLevel_ReviewCancel)
			}
			return finish("approved", "", protobuf.RiskLevel_ReviewAccept)
		case protobuf.TicketState_Rejected:
			terminal = true
			return finish("rejected", "command_review_rejected", protobuf.RiskLevel_ReviewReject)
		case protobuf.TicketState_Closed:
			terminal = true
			return finish("canceled", "command_review_canceled", protobuf.RiskLevel_ReviewCancel)
		default:
			return finish("failed", "command_review_failed", protobuf.RiskLevel_ReviewReject)
		}
	}
}

type reviewTicketResult struct {
	response *protobuf.CommandConfirmResponse
	err      error
}

func (p *WispProvider) createReviewTicket(ctx context.Context, request *protobuf.CommandConfirmRequest) (*protobuf.TicketInfo, error) {
	// Like gateway creation, ticket creation is not safe to retry after an
	// unknown outcome. Observe an in-flight creation after browser cancellation
	// under a bounded deadline and cancel any late returned ticket.
	requestCtx, cancel := p.rpcContext(context.WithoutCancel(ctx))
	results := make(chan reviewTicketResult, 1)
	go func() {
		defer cancel()
		response, err := p.client.CreateCommandTicket(requestCtx, request)
		results <- reviewTicketResult{response, err}
	}()
	select {
	case <-ctx.Done():
		go func() {
			result := <-results
			if result.response != nil && result.response.Info != nil && validTicketRequest(result.response.Info.CancelReq, false) {
				if err := p.enqueueTicketCancel(result.response.Info.CancelReq); err != nil {
					p.report("late_review_cleanup_persistence_failed")
				}
			}
		}()
		return nil, ctx.Err()
	case result := <-results:
		response := result.response
		if result.err != nil || response == nil || !statusOK(response.Status) || response.Info == nil || !validTicketRequest(response.Info.CheckReq, true) || !validTicketRequest(response.Info.CancelReq, false) {
			if response != nil && response.Info != nil && validTicketRequest(response.Info.CancelReq, false) {
				if err := p.enqueueTicketCancel(response.Info.CancelReq); err != nil {
					return nil, err
				}
			}
			return nil, errors.New("command_review_create_failed")
		}
		return proto.Clone(response.Info).(*protobuf.TicketInfo), nil
	}
}

func validTicketRequest(info *protobuf.ReqInfo, check bool) bool {
	if info == nil || info.Url == "" {
		return false
	}
	if check {
		return strings.EqualFold(info.Method, "GET")
	}
	return strings.EqualFold(info.Method, "DELETE") || strings.EqualFold(info.Method, "PUT")
}

func safeTicketURL(value string) string {
	u, err := url.Parse(value)
	if err != nil || u.User != nil || value == "" {
		return ""
	}
	if u.Scheme == "" && u.Host == "" && strings.HasPrefix(value, "/") && !strings.HasPrefix(value, "//") {
		return value
	}
	if (u.Scheme == "https" || u.Scheme == "http") && u.Host != "" {
		return value
	}
	return ""
}

func (p *WispProvider) enqueueTicketCancel(request *protobuf.ReqInfo) error {
	if err := p.spool.put(envelope{TicketCancel: &protobuf.TicketRequest{Req: proto.Clone(request).(*protobuf.ReqInfo)}}); err != nil {
		return errors.Join(ErrAuditDisk, err)
	}
	p.signal()
	return nil
}

var _ CommandAuthorizer = (*WispProvider)(nil)
