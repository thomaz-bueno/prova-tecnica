package com.example.gateway.config;

import com.example.gateway.entity.UserAccount;
import com.example.gateway.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestUserInitializerTest {

    @Mock
    private UserAccountRepository repository;

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    @Test
    void createsTheTestAccountWithABcryptHash() {
        when(repository.findByUsername("usuario")).thenReturn(Optional.empty());
        new TestUserInitializer(repository, encoder).run(new DefaultApplicationArguments());
        ArgumentCaptor<UserAccount> account = ArgumentCaptor.forClass(UserAccount.class);
        verify(repository).save(account.capture());
        assertThat(account.getValue().getUsername()).isEqualTo("usuario");
        assertThat(account.getValue().getRoles()).containsExactly("USER");
        assertThat(account.getValue().getPassword()).isNotEqualTo("senha123").startsWith("$2a$");
        assertThat(encoder.matches("senha123", account.getValue().getPassword())).isTrue();
        assertThat(encoder.matches("wrong-password", account.getValue().getPassword())).isFalse();
    }

    @Test
    void leavesAnExistingAccountAndItsPasswordUntouched() {
        UserAccount existing = new UserAccount("usuario", encoder.encode("changed-password"), Set.of("ADMIN"));
        when(repository.findByUsername("usuario")).thenReturn(Optional.of(existing));
        new TestUserInitializer(repository, encoder).run(new DefaultApplicationArguments());
        verify(repository, never()).save(any());
        assertThat(encoder.matches("changed-password", existing.getPassword())).isTrue();
        assertThat(existing.getRoles()).containsExactly("ADMIN");
    }

    @Test
    void doesNotEnableTheKnownTestAccountByDefault() {
        initializerContext().run(context -> assertThat(context).doesNotHaveBean(TestUserInitializer.class));
    }

    @Test
    void enablesTheKnownTestAccountOnlyWhenExplicitlyConfigured() {
        initializerContext().withPropertyValues("GATEWAY_SEED_TEST_USER=true")
                .run(context -> assertThat(context).hasSingleBean(TestUserInitializer.class));
    }

    private ApplicationContextRunner initializerContext() {
        return new ApplicationContextRunner()
                // Check the application's default independently of the developer's exported environment.
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(TestUserInitializer.class)
                .withBean(UserAccountRepository.class, () -> repository)
                .withBean(PasswordEncoder.class, () -> encoder);
    }
}
