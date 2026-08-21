package com.example;

import io.javalin.Javalin;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private static final Map<String, Map<String, Object>> usersDatabase = new HashMap<>();

    private Main() {
    }

    public static void main(String[] args) {
        seedDefaultUser();
        Javalin app = Javalin.create();

        app.get("/", ctx -> ctx.html(frontend()));
        app.get("/index.html", ctx -> ctx.html(frontend()));
        app.get("/api/autofill-profile", ctx -> ctx.json(publicProfile(usersDatabase.get("priya123"))));
        app.post("/api/signup", ctx -> {
            try {
                Map<String, Object> request = requestBody(ctx);
                String username = required(request, "username");

                synchronized (usersDatabase) {
                    if (usersDatabase.containsKey(username)) {
                        ctx.status(400).json(Map.of("error", "Username already exists"));
                        return;
                    }
                    usersDatabase.put(username, userRecord(request));
                }
                ctx.status(201).json(Map.of(
                        "message", "Account created successfully",
                        "profile", publicProfile(usersDatabase.get(username))
                ));
            } catch (IllegalArgumentException exception) {
                ctx.status(400).json(Map.of("error", exception.getMessage()));
            }
        });
        app.post("/api/login", ctx -> {
            try {
                Map<String, Object> request = requestBody(ctx);
                String username = required(request, "username");
                String password = required(request, "password");
                Map<String, Object> user = usersDatabase.get(username);

                if (user == null || !password.equals(user.get("password"))) {
                    ctx.status(401).json(Map.of("error", "Invalid username or password"));
                    return;
                }

                ctx.json(Map.of(
                        "message", "Login successful",
                        "username", username,
                        "profile", publicProfile(user)
                ));
            } catch (IllegalArgumentException exception) {
                ctx.status(400).json(Map.of("error", exception.getMessage()));
            }
        });
        app.get("/api/scheme/{schemeId}", ctx -> {
            String schemeId = ctx.pathParam("schemeId");
            Map<String, Object> scheme = schemes().get(schemeId);

            if (scheme == null) {
                ctx.status(404).json(Map.of("error", "Scheme not found"));
                return;
            }

            ctx.json(scheme);
        });

        app.start(7070);
    }

    static String greeting() {
        return "Hello, Maven!";
    }

    private static void seedDefaultUser() {
        if (!usersDatabase.isEmpty()) {
            return;
        }
        Map<String, Object> defaultUser = new LinkedHashMap<>();
        defaultUser.put("password", "password123");
        defaultUser.putAll(profile());
        usersDatabase.put("priya123", defaultUser);
    }

    private static Map<String, Object> requestBody(io.javalin.http.Context ctx) {
        try {
            return ctx.bodyAsClass(Map.class);
        } catch (Exception exception) {
            ctx.status(400).json(Map.of("error", "Request body must be valid JSON"));
            return Map.of();
        }
    }

    private static String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value.toString();
    }

    private static Map<String, Object> userRecord(Map<String, Object> request) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("password", required(request, "password"));
        user.put("fullName", required(request, "fullName"));
        user.put("dateOfBirth", required(request, "dateOfBirth"));
        user.put("address", required(request, "address"));
        user.put("phone", required(request, "phone"));
        user.put("email", required(request, "email"));
        user.put("governmentId", required(request, "governmentId"));
        return user;
    }

    private static Map<String, Object> publicProfile(Map<String, Object> user) {
        if (user == null) {
            return Map.of();
        }
        Map<String, Object> profile = new LinkedHashMap<>(user);
        profile.remove("password");
        return profile;
    }

    private static String frontend() {
        Path[] candidates = {
                Path.of("index.html"),
                Path.of("../index.html"),
                Path.of("../../index.html")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Unable to read frontend file: " + candidate, exception);
                }
            }
        }
        throw new IllegalStateException("Frontend file index.html was not found.");
    }

    private static Map<String, Object> profile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("fullName", "Priya Sharma");
        profile.put("dateOfBirth", "1990-01-15");
        profile.put("address", "42 Green Park Road, New Delhi, India");
        profile.put("phone", "+91-98765-43210");
        profile.put("email", "priya.sharma@example.com");
        profile.put("governmentId", "GOV-ID-PRIYA-1990");
        return profile;
    }

    private static Map<String, Map<String, Object>> schemes() {
        Map<String, Map<String, Object>> schemes = new LinkedHashMap<>();

        schemes.put("senior-citizen", scheme(
                "Senior Citizen Benefits Scheme",
                List.of("Government ID", "Proof of age", "Address proof", "Recent photograph"),
                "Submit your identity, age, and address documents through the nearest authorized service center.",
                "https://example.com/videos/senior-citizen-application"
        ));

        schemes.put("student-loan", scheme(
                "Student Loan Assistance Scheme",
                List.of("Government ID", "Admission letter", "Academic transcripts", "Income certificate"),
                "Complete the student loan application with your admission and financial documents, then submit it to a participating bank.",
                "https://example.com/videos/student-loan-application"
        ));

        return schemes;
    }

    private static Map<String, Object> scheme(
            String name,
            List<String> requiredDocuments,
            String instructions,
            String videoUrl
    ) {
        Map<String, Object> scheme = new LinkedHashMap<>();
        scheme.put("name", name);
        scheme.put("requiredDocuments", requiredDocuments);
        scheme.put("instructions", instructions);
        scheme.put("videoUrl", videoUrl);
        return scheme;
    }
}