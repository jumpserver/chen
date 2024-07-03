package org.jumpserver.chen.framework.jms.acl;

import lombok.Data;
import org.jumpserver.chen.wisp.Common;

@Data
public class ACLResult {
    private Common.RiskLevel riskLevel;

    private String CmdAclId;

    private String CmdGroupId;

}
