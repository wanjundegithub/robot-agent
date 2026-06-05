package robot.agent.apicenter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiRequestSafetyValidatorTest {

    private final ApiRequestSafetyValidator validator = new ApiRequestSafetyValidator();

    @Test
    void validateRequestUrl_allowsPublicHttpUrls() {
        assertThatCode(() -> validator.validateRequestUrl("https://example.com/users?userId=1"))
                .doesNotThrowAnyException();
    }

    @Test
    void validateRequestUrl_rejectsLoopbackAndPrivateHosts() {
        assertThatThrownBy(() -> validator.validateRequestUrl("http://127.0.0.1:8080/internal"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止访问内网或本机地址");

        assertThatThrownBy(() -> validator.validateRequestUrl("http://localhost:8080/internal"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止访问内网或本机地址");

        assertThatThrownBy(() -> validator.validateRequestUrl("http://192.168.1.10/internal"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止访问内网或本机地址");
    }
}
