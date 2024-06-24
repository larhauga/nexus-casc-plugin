package com.weareadaptive.nexus.casc.plugin.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InterpolatorTest {

    @Test
    void interpolateWithFile() {
        assertEquals("hello world", new Interpolator().interpolate("hello ${file:" + getClass().getClassLoader().getResource("test").getPath() + "}"));
        assertEquals("hello\nworld", new Interpolator().interpolate("${file:" + getClass().getClassLoader().getResource("test.multiline").getPath() + "}"));
        assertEquals("  hello\n  world\n", new Interpolator().interpolate("  ${file:" + getClass().getClassLoader().getResource("test.multiline").getPath() + "}"));
    }

    @Test
    void interpolateWithEnvVar() throws Exception {
        final String NEXUS_TEST_ENV_VAR = "NEXUS_TEST_ENV_VAR";
        withEnvironmentVariable(NEXUS_TEST_ENV_VAR, "any Value")
                .execute((Callable<List<String>>) () -> {
                    final Map.Entry<String, String> entry = System.getenv()
                            .entrySet()
                            .stream()
                            .filter(e -> NEXUS_TEST_ENV_VAR.equals(e.getKey()))
                            .findFirst()
                            .orElseThrow(() -> new RuntimeException("Mocking environment variables failed."));

                    final String key = entry.getKey();
                    final String value = entry.getValue();
                    assertEquals("hello " + value, new Interpolator().interpolate("hello $" + key));
                    assertEquals("hello " + value, new Interpolator().interpolate("hello ${" + key + "}"));
                    assertEquals("hello " + value, new Interpolator().interpolate("hello ${" + key + ":\"\"}"));
                    assertEquals("hello " + value, new Interpolator().interpolate("hello ${" + key + ":}"));
                    assertEquals("hello " + value, new Interpolator().interpolate("hello ${" + key + ":foo}"));
                    return null;
                });
    }

    @Test
    void interpolateWithNonExistingEnvVar() {
        assertEquals("hello $IDONOTEXIST", new Interpolator().interpolate("hello $IDONOTEXIST"));
        assertEquals("hello ${IDONOTEXIST}", new Interpolator().interpolate("hello ${IDONOTEXIST}"));
        assertEquals("hello ", new Interpolator().interpolate("hello ${IDONOTEXIST:}"));
        assertEquals("hello ", new Interpolator().interpolate("hello ${IDONOTEXIST:\"\"}"));
        assertEquals("hello world", new Interpolator().interpolate("hello ${IDONOTEXIST:world}"));
        assertEquals("hello world", new Interpolator().interpolate("hello ${IDONOTEXIST:\"world\"}"));
    }
}
