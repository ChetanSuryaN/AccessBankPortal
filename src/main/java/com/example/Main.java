package com.example;

import io.javalin.Javalin;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {
    private static final Map<String, Map<String, Object>> usersDatabase = new HashMap<>();

    private Main() {
    }

    public static void main(String[] args) {
        seedDefaultUser();

        // 1. Read Railway's dynamic PORT environment variable. Fallback to 7070 if local.
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7070"));

        // 2. Configure Javalin to automatically host static files from our resources folder
        // 2. Configure Javalin to automatically host static files from resource folder
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/static"); 
        });

        // 3. Fallback home routes
        // 3. Home routes
        app.get("/", ctx -> ctx.html(frontend()));
        app.get("/index.html", ctx -> ctx.html(frontend()));

@@ -160,7 +160,6 @@
        return profile;
    }

    // 4. Safe resource stream reads index.html from inside the jar bundle
    private static String frontend() {
        try (var inputStream = Main.class.getResourceAsStream("/static/index.html")) {
            if (inputStream == null) {
@@ -201,6 +200,62 @@
                "https://example.com"
        ));

        schemes.put("jan-dhan", scheme(
                "Pradhan Mantri Jan Dhan Yojana (PMJDY)",
                List.of("Aadhaar Card", "Voter ID", "PAN Card", "Passport size photo"),
                "Visit any bank branch or Bank Mitra outlet with your Aadhaar card to open a zero-balance account instantly.",
                "https://example.com"
        ));

        schemes.put("sukanya-samriddhi", scheme(
                "Sukanya Samriddhi Yojana (SSY)",
                List.of("Birth Certificate of girl child", "Parent/Guardian ID proof", "Address proof", "Photo"),
                "Fill out the SSY account opening form at your nearest post office or authorized commercial bank with guardian details.",
                "https://example.com"
        ));

        schemes.put("kisan-credit", scheme(
                "Kisan Credit Card (KCC) Scheme",
                List.of("Land Ownership Documents", "Identity Proof", "Address Proof", "Crop details"),
                "Submit land registry records and agricultural operation details at your local rural bank branch.",
                "https://example.com"
        ));

        schemes.put("atal-pension", scheme(
                "Atal Pension Yojana (APY)",
                List.of("Aadhaar Card", "Active Savings Bank Account", "Mobile number"),
                "Link your savings account and complete the auto-debit authorization form at your home bank branch.",
                "https://example.com"
        ));

        schemes.put("home-loan", scheme(
                "Pradhan Mantri Awas Yojana (PMAY) Home Loan",
                List.of("Income Proof", "Property valuation report", "Aadhaar Card", "Bank statements (6 months)"),
                "Apply online or visit an approved housing finance lender with property documents and salary/income statements.",
                "https://example.com"
        ));

        schemes.put("mudra-loan", scheme(
                "Pradhan Mantri MUDRA Yojana (PMMY)",
                List.of("Business Registration certificate", "Identity Proof", "Bank statement", "Business plan outline"),
                "Submit your business proposal and identity records to any commercial bank, RRB, or MFI offering Mudra loans.",
                "https://example.com"
        ));

        schemes.put("fixed-deposit", scheme(
                "Tax Saver Fixed Deposit Scheme",
                List.of("PAN Card", "Aadhaar Card", "Existing Savings account details"),
                "Deposit funds for a locked term of 5 years through net banking or by filling an FD request form at the branch.",
                "https://example.com"
        ));

        schemes.put("nsc", scheme(
                "National Savings Certificate (NSC)",
                List.of("Identity Proof", "Address Proof", "PAN Card", "Purchase form"),
                "Purchase NSC certificates directly through your local post office branch or authorized online portal.",
                "https://example.com"
        ));

        return schemes;
    }

@@ -212,6 +267,7 @@
    ) {
        Map<String, Object> scheme = new LinkedHashMap<>();
        scheme.put("name", name);
        scheme.put("schemeName", name);
        scheme.put("requiredDocuments", requiredDocuments);
        scheme.put("instructions", instructions);
        scheme.put("videoUrl", videoUrl);
