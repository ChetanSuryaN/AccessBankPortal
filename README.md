# 🏦 AccessBank Portal

### ♿ Banking Without Barriers — Accessible, Inclusive & User-Friendly Digital Banking

> **Technology should adapt to people — people should not have to adapt to technology.**

AccessBank Portal is an **accessibility-first digital banking platform** designed to make digital banking and financial information easier, safer, and more understandable for people with different abilities, language barriers, and varying levels of digital literacy.

This project was developed for the **Accessibility & Inclusive Technology** hackathon problem statement.

### 🔗 Quick Links

- 🌐 **[Live Demo](https://accessbankportal-production-4a7c.up.railway.app/)**
- 💻 **[GitHub Repository](https://github.com/ChetanSuryaN/AccessBankPortal)**

---

## 🎯 Problem Statement

Millions of people in India face digital exclusion due to visual, hearing, motor, cognitive, language, or digital-literacy barriers.

Everyday digital tasks such as:

- Reading information
- Filling forms
- Understanding financial services
- Navigating digital platforms
- Accessing security information
- Entering login details

can become difficult when websites are designed only for an "average" user.

### 💡 Our Goal

We wanted to build a banking experience that **adapts to the user**, rather than forcing every user to interact with technology in the same way.

---

# 💡 Our Solution

**AccessBank Portal** combines accessibility features, simplified information, security awareness, form assistance, and financial-service information into one user-friendly platform.

The platform allows users to customize their experience and access important banking-related services with fewer digital barriers.

---

# ⭐ Key Features

## ♿ Accessibility Features

### 🔤 Adjustable Text Size

Users can increase, decrease, or reset the text size according to their needs.

### 🎨 High Contrast Mode

A high-contrast interface improves visibility and readability for users who benefit from stronger visual contrast.

### ⌨️ On-Screen Keyboard

A virtual keyboard provides an alternative way of entering information and supports users who may have difficulty with a physical keyboard.

### 🔊 Read Security Alert Aloud

Important security information can be read aloud using voice assistance, helping users who have difficulty reading on-screen content.

### 🎚️ Voice Assistance Controls

Users can adjust the voice experience, including speech speed, according to their preference.

### 🧠 Simplified Information

Complex financial information can be presented in a simpler and more understandable form.

---

# 🛡️ Security & Safety

Accessibility should not come at the cost of security.

AccessBank Portal includes security-awareness information to help users recognize common digital banking scams, including:

- 🔑 Password, PIN and OTP scams
- 📞 Fake customer-support numbers
- ⚠️ Urgency-based scams
- 💻 Malicious remote-control applications

### 🚨 Emergency Account Protection

An emergency account-freeze option provides a clear action for users who suspect unauthorized activity.

> **When something goes wrong, the safest action should be easy to find.**

---

# 📝 Accessible Form Verification

Digital forms can be a major barrier for users with limited digital experience or accessibility needs.

The portal provides a **Form Verification Registry** with:

- Accessible form interaction
- Auto-fill assistance
- Verification support
- Clear input fields

The goal is to reduce unnecessary manual effort and make digital form completion easier.

---

# 🏛️ Institutional Support Services

The portal provides easy access to information related to important financial services and schemes, including:

1. Senior Citizen Pension
2. Student Education Loan
3. PM Jan Dhan Yojana
4. Sukanya Samriddhi
5. Kisan Credit Card
6. Atal Pension Scheme
7. Home Loan Support
8. Mudra Micro Business
9. Fixed Term Deposit
10. National Certificate

---

# 🌟 What Makes AccessBank Portal Different?

| Traditional Digital Experience | AccessBank Portal |
|---|---|
| Designed mainly for average users | Designed for diverse users |
| Fixed text size | Adjustable text size |
| Standard visual interface | High-contrast accessibility mode |
| Primarily visual interaction | Read-aloud assistance |
| Complex information | Simplified information |
| Forms can be difficult to complete | Accessible form assistance |
| Security information may be difficult to find | Security awareness is highlighted |
| One interaction style | Multiple accessibility options |

### Our Core Approach

**Accessibility + Simplicity + Safety + Inclusion**

---

# 🚀 Innovation

### 1. Accessibility at the Core

Accessibility is integrated directly into the user experience rather than being treated as an afterthought.

### 2. One Platform, Multiple User Needs

Different users can use different accessibility features according to their individual requirements.

### 3. Accessibility + Security

The platform combines inclusive design with security awareness.

### 4. Low Digital Literacy

The solution is designed not only for people with disabilities but also for users who may find complex digital platforms difficult to understand.

### 5. Indian Context

The platform provides easy access to financial schemes and services relevant to users in India.

---

# 🏗️ System Architecture

```text
                         👤 USER
                           │
                           ▼
                ┌─────────────────────┐
                │    HTML FRONTEND    │
                │                     │
                │ • Banking Interface │
                │ • Accessibility UI  │
                │ • Forms             │
                │ • Services          │
                └──────────┬──────────┘
                           │
                           ▼
                ┌─────────────────────┐
                │    JAVA BACKEND     │
                │                     │
                │ • Application Logic │
                │ • Authentication    │
                │ • Data Processing   │
                └──────────┬──────────┘
                           │
                           ▼
                ┌─────────────────────┐
                │       HASHMAP       │
                │                     │
                │ In-Memory Data      │
                │ Handling             │
                └─────────────────────┘
```

---

# 🛠️ Technology Stack

| Component | Technology |
|---|---|
| Frontend | HTML |
| Backend | Java |
| Data Handling | Java HashMap |
| Database | Not used |
| External APIs | Not used |
| Build Tool | Maven |
| Deployment | Railway |

### Why HashMap?

For this hackathon prototype, a database was not required. Java `HashMap` is used for lightweight in-memory data handling.

---

# 🔄 User Journey

```text
                         START
                           │
                           ▼
                    Open Portal
                           │
                           ▼
              Choose Accessibility Options
                           │
                           ▼
                 Access Banking Services
                           │
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
         📝 Forms       🏛️ Services     🛡️ Security
            │              │              │
            └──────────────┼──────────────┘
                           ▼
                  Safer & Easier
                     Experience
```

---

# ⚙️ Run the Project Locally

## Prerequisites

Make sure you have:

- Java JDK
- Maven
- Git

## 1. Clone the Repository

```bash
git clone https://github.com/ChetanSuryaN/AccessBankPortal.git
```

## 2. Enter the Project Directory

```bash
cd AccessBankPortal
```

## 3. Build the Project

```bash
mvn clean install
```

## 4. Run the Application

Run the Java application using the project's configured Maven setup.

---

# 🌐 Live Demo

### 🚀 Try AccessBank Portal

**[Open the Live Website →](https://accessbankportal-production-4a7c.up.railway.app/)**

Explore the accessibility controls, security features, form assistance, and institutional support services.

---

# 🔮 Future Scope

The prototype can be expanded into a complete inclusive digital-finance platform.

### 🌐 Regional Language Support

### 🤖 AI Accessibility Assistant

An AI assistant could help users with:

- Banking services
- Forms
- Financial schemes
- Security warnings
- Frequently asked questions

### 🗄️ Database Integration

A database could be added for persistent user accounts and real-world data management.

### 🔐 Advanced Authentication

Future versions could include:

- Multi-factor authentication
- Biometric authentication
- Device verification
- Fraud detection

### 📱 Mobile Application

The platform could be extended into a dedicated Android/iOS application.

### 🏦 Secure Banking Integration

Future versions could integrate with authorized banking systems and APIs.

---

# 🌍 Expected Impact

### 👁️ Users with Visual Difficulties

Improved readability, adjustable text, high contrast, and voice assistance.

### 🖐️ Users with Motor Difficulties

Alternative interaction through on-screen keyboard and simplified controls.

### 🧠 Users with Low Digital Literacy

Simplified information and easier navigation.

### 👴 Senior Citizens

Larger text, clearer instructions, security awareness, and easier access to services.

### 🇮🇳 Wider Community

A more inclusive approach to accessing digital financial information and services.

---

# 🏆 Hackathon Alignment

### Problem Statement

**Accessibility & Inclusive Technology**

### Our Approach

```text
Accessibility
      ↓
Ease of Use
      ↓
Understanding
      ↓
Security
      ↓
Inclusion
```

Our solution focuses on removing digital barriers and creating a more accessible, intuitive, and dignified digital banking experience.

---

# 👨‍💻 Team

| Team Member |
|---|
| **Chetan Surya N** |
| **Cheluvaraj S N** |
| **Goutham U** |

---

# 🎯 Our Vision

> ### "A banking experience where accessibility is not a feature — it is the foundation."

We believe technology should remove barriers instead of creating them.

## **AccessBank Portal — Banking Without Barriers.** ❤️

---

## 🔗 Project Links

- 🌐 **[Live Demo](https://accessbankportal-production-4a7c.up.railway.app/)**
- 💻 **[GitHub Repository](https://github.com/ChetanSuryaN
