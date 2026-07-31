package com.brutex.warehousebrain;

import com.brutex.warehousebrain.service.WarehouseBrainService;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "notion.api-key=test-token",
        "notion.projects-data-source-id=ds-projects-test",
        "notion.archives-data-source-id=ds-archives-test",
        "notion.template-id=tpl-test"
})
class WarehouseBrainApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void mcpServerStartsWithWarehouseTools() {
        assertThat(context.getBean(McpSyncServer.class)).isNotNull();
        assertThat(context.getBean(WarehouseBrainService.class)).isNotNull();
    }
}
