package se.tcmt.muninn.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import se.tcmt.muninn.service.DomainPolicy;
import se.tcmt.muninn.support.AbstractPostgresIT;
import se.tcmt.muninn.support.FakeIdp;

@SpringBootTest
@AutoConfigureMockMvc
class DomainPolicyIT extends AbstractPostgresIT {

    private static final String TENANT = "6f1c2c3a-0000-4a6e-9b1e-1234567890ab";

    @Autowired
    DomainPolicy policy;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("delete from app_setting");
        FakeIdp.put(jdbc, "auth.allowedDomains", " acme.com, Example.ORG ");
    }

    @Test
    void allowsListedDomainCaseInsensitive() {
        assertThat(policy.allows("Ann@ACME.com", true, null)).isTrue();
        assertThat(policy.allows("bob@example.org", true, null)).isTrue();
    }

    @Test
    void rejectsUnverifiedEmail() {
        assertThat(policy.allows("ann@acme.com", false, null)).isFalse();
    }

    @Test
    void rejectsUnlistedDomain() {
        assertThat(policy.allows("ann@evil.com", true, null)).isFalse();
        assertThat(policy.allows("ann@sub.acme.com", true, null)).isFalse();
        assertThat(policy.allows("ann@acme.com.evil.com", true, null)).isFalse();
        assertThat(policy.allows("ann@evil.com@acme.com", true, null)).isFalse();
        assertThat(policy.allows("acme.com", true, null)).isFalse();
        assertThat(policy.allows("@acme.com", true, null)).isFalse();
        assertThat(policy.allows(null, true, null)).isFalse();
    }

    @Test
    void rejectsEveryoneWhenAllowListMissingOrEmpty() {
        FakeIdp.put(jdbc, "auth.allowedDomains", " , ");
        assertThat(policy.allows("ann@acme.com", true, null)).isFalse();
        jdbc.update("delete from app_setting");
        assertThat(policy.allows("ann@acme.com", true, null)).isFalse();
    }

    @Test
    void rejectsWrongTenantWhenPinned() {
        FakeIdp.put(jdbc, "auth.tenantId", TENANT);

        assertThat(policy.allows("ann@acme.com", true, "11111111-2222-3333-4444-555555555555"))
                .isFalse();
        assertThat(policy.allows("ann@acme.com", true, null)).isFalse();
        assertThat(policy.allows("ann@acme.com", true, TENANT.toUpperCase())).isTrue();
        assertThat(policy.allows("ann@evil.com", true, TENANT)).isFalse();
    }
}
