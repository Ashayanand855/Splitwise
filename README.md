# Splitwise India - Fullstack Java Edition

A lightweight, zero-dependency fullstack expense-sharing application built with Java and modern web technologies. It supports debt simplification, multi-channel interfaces (Web, Terminal CLI, and Swing Desktop GUI), and RESTful API endpoints.

---

## 🌟 Key Features

- **Triple Interface Support**:
  - **Modern Web GUI**: Rich HTML5/CSS/JavaScript interface served at `http://localhost:8080`.
  - **Interactive CLI**: Terminal-based console menu for fast terminal operations.
  - **Desktop GUI**: Native Java Swing window application (`SplitwiseDesktopGUI`).
- **Debt Simplification**: Greedy minimum-cash-flow algorithm reducing settlement transactions.
- **REST API**: Embedded HTTP server exposing `/api/group` endpoints for programmatic control.
- **Precision Accounting**: All currency calculations handled in integer paise/cents to avoid floating-point issues.
- **Zero External Dependencies**: Pure Java Standard Library (`com.sun.net.httpserver`, `java.awt`, `javax.swing`).

---

## 🚀 Quick Start

### Prerequisites
- Java JDK 11 or higher

### Compile
```bash
javac SplitwiseApp.java SplitwiseDesktopGUI.java
```

### Run

#### 1. Fullstack Server + Web GUI + Interactive CLI
```bash
java SplitwiseApp
```
- Open [http://localhost:8080](http://localhost:8080) in your browser.
- Use the terminal prompt to manage groups and expenses directly.

#### 2. Native Desktop GUI (Swing)
```bash
java SplitwiseDesktopGUI
```

---

## 📁 Project Structure

```
├── SplitwiseApp.java        # Core logic, REST API server, CLI, and models
├── SplitwiseDesktopGUI.java # Native desktop Swing GUI client
├── index.html               # Frontend single-page application (served at :8080)
└── README.md                # Project documentation
```

---

## 🔌 API Reference

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/group?id=<id>` | Fetch group state, members, balances, and history |
| `POST` | `/api/group` | Create a new group |
| `POST` | `/api/group/member` | Add a member to a group |
| `POST` | `/api/group/expense` | Record a new expense |
| `POST` | `/api/group/settle` | Settle debts between members |
| `GET` | `/` | Serves `index.html` web application |