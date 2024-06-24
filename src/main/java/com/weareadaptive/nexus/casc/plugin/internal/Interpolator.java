package com.weareadaptive.nexus.casc.plugin.internal;

import org.sonatype.goodies.common.ComponentSupport;

import javax.inject.Named;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Named
public class Interpolator extends ComponentSupport {
    public String interpolate(String str) {
        String pattern = "(^\\s+)?\\$(([A-Z0-9_]+)|\\{([^:}]+)(:(\"([^\"}]*)\"|([^}]*)))?})";
        Pattern expr = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher matcher = expr.matcher(str);
        while (matcher.find()) {
            String varName = matcher.group(3);
            if (varName == null) {
                varName = matcher.group(4);
            }
            String defaultValue = matcher.group(7);
            if (defaultValue == null) {
                defaultValue = matcher.group(8);
            }

            String value = null;

            if ("file".equalsIgnoreCase(varName)) {
                if (defaultValue == null || defaultValue.trim().isEmpty()) {
                    log.error("Missing filename in {}", str);
                    continue;
                }

                File f = new File(defaultValue);

                if (!f.exists()) {
                    log.error("File {} does not exist", f.getAbsolutePath());
                    continue;
                }

                try {
                    value = new String(Files.readAllBytes(Paths.get(f.getAbsolutePath())), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.error("Failed to read file {}", defaultValue);
                }
            } else {
                value = System.getenv(varName.toUpperCase());
            }

            if (value == null) {
                if (defaultValue == null) {
                    log.warn("Found no value to interpolate variable {}", varName);
                    continue;
                }

                value = defaultValue;
            }

            // If the variable is prefixed with only whitespaces, we need to prefix all lines in
            // the value with the same whitespaces to keep the indentation.
            String prefixWhitespaces = matcher.group(1);
            if (prefixWhitespaces != null) {
                String[] lines = value.split("\r\n|\r|\n");
                StringBuilder sb = new StringBuilder();
                for (String line : lines) {
                    sb.append(prefixWhitespaces).append(line).append(System.lineSeparator());
                }
                value = sb.toString();
            }

            Pattern subexpr = Pattern.compile(Pattern.quote(matcher.group(0)));
            str = subexpr.matcher(str).replaceAll(value);
        }

        return str;
    }
}
