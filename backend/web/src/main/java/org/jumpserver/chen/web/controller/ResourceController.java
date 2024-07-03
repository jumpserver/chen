package org.jumpserver.chen.web.controller;

import org.jumpserver.chen.framework.datasource.entity.action.Action;
import org.jumpserver.chen.framework.datasource.entity.action.ActionRequest;
import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.framework.datasource.entity.form.FormData;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.entity.GetHintsRequest;
import org.jumpserver.chen.web.service.ResourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {


    @Autowired
    private ResourceService resourceService;


    @PostMapping("/children")
    public List<TreeNode> getChild(@RequestBody(required = false) TreeNode node,
                                   @RequestParam(required = false) boolean force) {

        try {
            var children = this.resourceService.getChildren(node, force);
            return children;
        } catch (Exception e) {
            throw e;
        }
    }

    @PostMapping("/actions")
    public List<Action> getActions(@RequestBody TreeNode node) {
        return this.resourceService.getActions(node);
    }

    @PostMapping("/actions/do")
    public EventEmitter doAction(@RequestBody ActionRequest actionRequest) {
        return this.resourceService.doAction(actionRequest.getNode(), actionRequest.getAction());
    }

    @PostMapping("/forms")
    public EventEmitter submitResourceFormData(@RequestBody FormData form) throws SQLException {
        return this.resourceService.submitResourceForm(form);
    }

    @PostMapping("/hints")
    public Map<String, List<String>> getHints(@RequestBody GetHintsRequest request) throws SQLException {
        return SessionManager
                .getCurrentSession()
                .getDatasource()
                .getResourceBrowser()
                .getSQLHintsHandler()
                .getHints(request.getNodeKey(), request.getContext());
    }

}
