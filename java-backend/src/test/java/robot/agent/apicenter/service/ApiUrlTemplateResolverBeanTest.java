package robot.agent.apicenter.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import static org.assertj.core.api.Assertions.assertThat;

class ApiUrlTemplateResolverBeanTest {

    @Test
    void resolverIsDiscoveredAsSpringComponent() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true);

        boolean discovered = scanner.findCandidateComponents("robot.agent.apicenter.service").stream()
                .anyMatch(candidate -> ApiUrlTemplateResolver.class.getName().equals(candidate.getBeanClassName()));

        assertThat(discovered).isTrue();
    }
}
