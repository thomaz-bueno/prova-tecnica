package com.example.gateway.config;

import com.example.gateway.entity.UserAccount;
import com.example.gateway.repository.UserAccountRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Component
@ConditionalOnProperty(name = "security.seed-test-user", havingValue = "true")
public class TestUserInitializer implements ApplicationRunner {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public TestUserInitializer(UserAccountRepository userAccountRepository,
                               PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userAccountRepository.findByUsername("usuario").isPresent()) {
            return;
        }
        userAccountRepository.save(new UserAccount(
                "usuario", passwordEncoder.encode("senha123"), Set.of("USER")));
    }
}
