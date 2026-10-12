package com.runningai.integration.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server switch is the OR of the two tool-group switches, for every combination, and the
 * computed value deliberately has the FIRST property-source position - a stray direct setting of
 * {@code spring.ai.mcp.server.enabled} can never leave the server up with zero RunningAI tool
 * groups enabled (Phase 7C-2b STEP 1 regression).
 */
class McpServerSwitchEnvironmentPostProcessorTest {

    private final McpServerSwitchEnvironmentPostProcessor processor = new McpServerSwitchEnvironmentPostProcessor();

    @ParameterizedTest
    @CsvSource({
            "false, false, false",
            "true,  false, true",
            "false, true,  true",
            "true,  true,  true",
    })
    void serverSwitchIsTheOrOfWriteAndRead(boolean write, boolean read, boolean expected) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("running-ai.mcp.enabled", String.valueOf(write))
                .withProperty("running-ai.mcp.read-enabled", String.valueOf(read));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.mcp.server.enabled", Boolean.class)).isEqualTo(expected);
    }

    @Test
    void bothSwitchesAbsentMeansOff() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.mcp.server.enabled", Boolean.class)).isFalse();
    }

    @Test
    void aDirectServerEnabledSettingIsOverriddenByTheComputedValue() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.mcp.server.enabled", "true"); // both tool groups off

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.mcp.server.enabled", Boolean.class))
                .as("the computed OR must win over a direct setting, so the server can never be up with no tool group")
                .isFalse();
    }
}
