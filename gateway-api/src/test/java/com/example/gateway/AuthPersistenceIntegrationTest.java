package com.example.gateway;

import com.example.gateway.config.TestUserInitializer;
import com.example.gateway.entity.UserAccount;
import com.example.gateway.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "security.seed-test-user=true"})
@AutoConfigureMockMvc
@Transactional
class AuthPersistenceIntegrationTest {

    private static final String SECRET = TestJwtSecrets.randomSecret();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository repository;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TestUserInitializer initializer;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureTestEnvironment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("GATEWAY_TEST_DB_URL",
                "jdbc:h2:mem:gateway_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("GATEWAY_TEST_DB_USERNAME", "sa"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("GATEWAY_TEST_DB_PASSWORD", ""));
        registry.add("security.jwt.secret", () -> SECRET);
        registry.add("security.jwt.issuer", () -> "gateway-api");
    }

    @Test
    void persistsTheTestUserWithABcryptHashAndItsRole() {
        UserAccount user = repository.findByUsername("usuario").orElseThrow();
        assertThat(user.getId()).isNotNull();
        assertThat(user.getRoles()).containsExactly("USER");
        String storedPassword = jdbcTemplate.queryForObject(
                "select password_hash from gateway_users where username = ?", String.class, "usuario");
        assertThat(storedPassword).isNotEqualTo("senha123").startsWith("$2a$");
        assertThat(encoder.matches("senha123", storedPassword)).isTrue();
    }

    @Test
    void keepsTheSameAccountAndHashAcrossRepeatedInitialization() {
        UserAccount original = repository.findByUsername("usuario").orElseThrow();
        initializer.run(new DefaultApplicationArguments());
        initializer.run(new DefaultApplicationArguments());
        UserAccount current = repository.findByUsername("usuario").orElseThrow();
        assertThat(current.getId()).isEqualTo(original.getId());
        assertThat(current.getPassword()).isEqualTo(original.getPassword());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from gateway_users where username = ?", Long.class, "usuario"))
                .isEqualTo(1L);
    }

    @Test
    void authenticatesThePersistedUserAndRejectsIncorrectCredentials() throws Exception {
        String response = login("usuario", "senha123", 200);
        Jwt jwt = decoder.decode(objectMapper.readTree(response).get("token").asText());
        assertThat(jwt.getSubject()).isEqualTo("usuario");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds()).isEqualTo(3600);
        login("usuario", "wrong-password", 401);
        login("unknown", "senha123", 401);
    }

    @Test
    void doesNotResetAnExistingPasswordDuringInitialization() throws Exception {
        String changedHash = encoder.encode("changed-password");
        jdbcTemplate.update("update gateway_users set password_hash = ? where username = ?",
                changedHash, "usuario");
        initializer.run(new DefaultApplicationArguments());
        assertThat(jdbcTemplate.queryForObject(
                "select password_hash from gateway_users where username = ?", String.class, "usuario"))
                .isEqualTo(changedHash);
        login("usuario", "changed-password", 200);
        login("usuario", "senha123", 401);
    }

    private String login(String username, String password, int expectedStatus) throws Exception {
        var result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 401) {
            result.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        return result.andReturn().getResponse().getContentAsString();
    }
}
