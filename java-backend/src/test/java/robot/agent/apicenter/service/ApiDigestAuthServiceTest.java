package robot.agent.apicenter.service;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiDigestAuthServiceTest {

    private final ApiDigestAuthService service = new ApiDigestAuthService();

    @Test
    void buildsDigestAuthorizationFromChallenge() {
        String challenge = "Digest realm=\"test\", nonce=\"abc\", qop=\"auth\", opaque=\"xyz\"";

        String header = service.buildAuthorization(
                challenge,
                "GET",
                URI.create("https://example.com/users"),
                Map.of("username", "demo", "password", "secret")
        );

        assertThat(header).startsWith("Digest ");
        assertThat(header).contains("username=\"demo\"");
        assertThat(header).contains("realm=\"test\"");
        assertThat(header).contains("nonce=\"abc\"");
        assertThat(header).contains("uri=\"/users\"");
        assertThat(header).contains("qop=auth");
        assertThat(header).contains("nc=00000001");
        assertThat(header).contains("cnonce=\"");
        assertThat(header).contains("response=\"");
        assertThat(header).contains("opaque=\"xyz\"");
    }
}
