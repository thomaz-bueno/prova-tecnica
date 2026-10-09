package com.example.gateway.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayCorsPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example", "null", "file:///tmp/client", "https://client.example/path",
            "https://client.example/", "https://user:password@client.example", "https://client.example?x=1",
            "https://client.example#fragment", "http://localhost:65536"})
    void rejectsWildcardAndNonOriginConfiguration(String origin) {
        assertThatThrownBy(() -> new GatewayCorsProperties(List.of(origin)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CORS origins must be explicit HTTP(S) origins");
    }
}
