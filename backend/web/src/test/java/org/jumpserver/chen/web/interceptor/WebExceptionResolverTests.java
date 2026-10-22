package org.jumpserver.chen.web.interceptor;

import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.web.controller.ResourceController;
import org.jumpserver.chen.web.exception.ChenException;
import org.jumpserver.chen.web.service.ResourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.SQLException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebExceptionResolverTests {
    private MockMvc resourceRequest(Exception error) {
        ResourceService service = mock(ResourceService.class);
        when(service.getChildren(any(TreeNode.class), anyBoolean()))
                .thenThrow(new ChenException("获取子节点失败", error));
        ResourceController controller = new ResourceController();
        ReflectionTestUtils.setField(controller, "resourceService", service);
        return MockMvcBuilders.standaloneSetup(controller)
                .setHandlerExceptionResolvers(new WebExceptionResolver())
                .build();
    }

    @ParameterizedTest
    @CsvSource({"4060,S0001", "916,S0001", "229,S0005", "1031,42000", "0,42501"})
    void resourcePermissionErrorsReturnReadableForbidden(int code, String sqlState) throws Exception {
        SQLException error = new SQLException("database access denied", sqlState, code);
        resourceRequest(new SQLException("reflection wrapper", error))
                .perform(post("/api/resources/children")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"restricted\",\"type\":\"database\",\"key\":\"database=restricted\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string("权限不足，无法访问该数据库或资源，请联系数据库管理员授权。"));
    }

    @Test
    void permissionErrorInJdbcExceptionChainIsRecognized() throws Exception {
        SQLException error = new SQLException("connection pool wrapper");
        error.setNextException(new SQLException("cannot open database", "S0001", 4060));
        resourceRequest(error)
                .perform(post("/api/resources/children")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"restricted\"}"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"102,S0001", "1031,HY000", "942,42000", "18456,28000"})
    void unrelatedDatabaseErrorsKeepTheirOriginalMessage(int code, String sqlState) throws Exception {
        resourceRequest(new SQLException("original database error", sqlState, code))
                .perform(post("/api/resources/children")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"restricted\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("获取子节点失败:original database error"));
    }
}
