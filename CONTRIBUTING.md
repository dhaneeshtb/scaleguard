# Contributing to ScaleGuard

Hi there! 👋  
Thank you for considering contributing to **ScaleGuard** — the all-in-one service exposure platform.  
We welcome all kinds of contributions — from bug reports and feature requests to documentation improvements and code enhancements.

---

## 📜 Code of Conduct

We are committed to fostering a welcoming and respectful community.  
Please read and follow our [Code of Conduct](CODE_OF_CONDUCT.md).

---

## 🎯 Where to start?

### Good first issues

Look for issues labeled [`good first issue`](https://github.com/dhaneeshtb/scaleguard/labels/good%20first%20issue) — these are carefully curated for newcomers:

- Adding tests for existing components
- Documentation improvements
- Minor bug fixes
- Adding new load balancing strategies

### Areas we'd love help with

| Area | Difficulty | Impact |
|---|---|---|
| 🧪 **Test coverage** — Add tests for `RouteTable`, `ConfigManager`, DNS | Easy | High |
| 📖 **Documentation** — Usage examples, tutorials, blog posts | Easy | High |
| 🔌 **Helm chart** — Kubernetes deployment support | Medium | High |
| 📊 **OpenAPI spec** — Swagger docs for the admin API | Medium | High |
| 🌐 **gRPC proxy** — Support for gRPC traffic | Hard | Medium |
| 🔐 **OAuth2/OIDC** — Enterprise auth integration | Hard | High |

---

## 🚀 Development Setup

### Prerequisites

- **Java JDK 11+** (we recommend Amazon Corretto)
- **Maven 3.6+**
- **Git**
- **Node.js 16+** (only for Admin UI development)

### Building from source

```bash
# Clone the repository
git clone https://github.com/dhaneeshtb/scaleguard.git
cd scaleguard

# Build (includes tests)
mvn clean install

# Build without tests (faster)
mvn clean install -DskipTests

# Run tests only
mvn test
```

### Running locally

```bash
export SCALEGUARD_ADMIN_USER=admin
export SCALEGUARD_ADMIN_PASSWORD=dev-password-123

java -Dport=8080 \
     -DadminUser=$SCALEGUARD_ADMIN_USER \
     -DadminPassword=$SCALEGUARD_ADMIN_PASSWORD \
     -jar target/scaleguard-1.0-SNAPSHOT.jar
```

### Verify it's working

```bash
curl http://localhost:8080/healthz    # Detailed health
curl http://localhost:8080/metrics    # Prometheus metrics
curl http://localhost:8080/stats      # Route statistics
```

### Project structure

```
scaleguard/
├── src/main/java/com/scaleguard/server/
│   ├── http/
│   │   ├── reverse/       # Core proxy logic (frontend/backend handlers)
│   │   ├── router/        # Route table, host selection, rate limiting
│   │   ├── metrics/       # Prometheus metrics handler
│   │   ├── cache/         # Request caching
│   │   ├── auth/          # JWT authentication
│   │   └── async/         # Async flow engine
│   ├── dns/               # Built-in DNS server
│   ├── ssh/               # SafeExpose SSH tunneling
│   ├── certificates/      # ACME/Let's Encrypt integration
│   ├── db/                # SQLite/PostgreSQL persistence
│   └── licencing/         # Licensing system
├── src/test/java/         # Test suite
├── admin-ui/              # React admin dashboard
├── docker/                # Dockerfile
└── .github/workflows/     # CI/CD pipeline
```

---

## 📝 Submitting a Pull Request

### 1. Fork & branch

```bash
# Fork via GitHub UI, then:
git clone https://github.com/YOUR-USERNAME/scaleguard.git
cd scaleguard
git checkout -b feat/your-feature-name   # or fix/bug-name
```

### 2. Make your changes

- Write clean, readable code
- Add tests for new functionality
- Update documentation if needed

### 3. Test

```bash
mvn clean test
```

All tests must pass before submitting.

### 4. Commit

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add weighted round-robin load balancing
fix: resolve null pointer in DNS handler
docs: add configuration API examples
test: add RouteTable unit tests
refactor: extract host selection strategies
```

### 5. Push & PR

```bash
git push origin feat/your-feature-name
```

Then open a Pull Request via GitHub. Include:
- **What** you changed and **why**
- Related issue number (e.g., `Closes #42`)
- Screenshots if it's a UI change

---

## 🧑‍💻 Coding Guidelines

### Style

- **Java 11** — Use Java 11 features but nothing higher
- **Naming** — Standard Java naming conventions (camelCase methods, PascalCase classes)
- **Logging** — Use SLF4J (`logger.info/debug/error`), not `System.out.println` or `e.printStackTrace()`
- **Null safety** — Check for null. Prefer returning empty collections over null
- **Tests** — Use JUnit 4 (already in the project). Name tests descriptively: `testRoundRobinSkipsUnreachableHosts`

### What makes a great PR?

✅ Small, focused changes (one concern per PR)  
✅ Tests included  
✅ Documentation updated  
✅ Commit messages follow conventional format  
✅ No unrelated formatting changes  

---

## ❓ Questions?

- Open a [Discussion](https://github.com/dhaneeshtb/scaleguard/discussions) for questions and ideas
- File an [Issue](https://github.com/dhaneeshtb/scaleguard/issues) for bugs and feature requests
- Email: dhaneeshtnair@gmail.com

---

Thank you for helping make ScaleGuard better! 🛡️
