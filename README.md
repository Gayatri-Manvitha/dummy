# Arch Helper

A small full-stack tool that takes a **git repository URL** and produces:

- **Architecture explanation** — primary language, frameworks, build tools, detected layers (Controller / Service / Repository …) and a module tree.
- **Code-flow sequence diagrams** — for each REST endpoint **or Java Servlet** it traces calls from the controller/servlet through services into repositories/DAOs and renders them as **Mermaid** sequence diagrams (`Client → Controller/Servlet → Service → Repository/DAO → Database`, and `→ JSP view` for forward/redirect).
- **Database usage** — JPA `@Query` (JPQL & native), inline SQL strings, MyBatis mapper statements, `.sql` scripts, **JSP scriptlet SQL and JSTL `<sql:query>`/`<sql:update>` tags**, and **stored procedures** (`@Procedure`, `CallableStatement`, `{call …}`, `EXEC`, `CREATE PROCEDURE`). Also counts Spring Data derived queries (`findBy…`).
- **Outputs** — every HTTP endpoint with its method, path, handler and declared return/output type (servlets show the forwarded/redirected **JSP view**).

> The analyzers are heuristic and tuned for **Java** projects: **Spring** (Controller/Service/Repository) and **legacy Servlet + JSP** apps (with general SQL/MyBatis/JSTL detection). They run on the cloned source without compiling it.

### Supported inputs

- Full git URL: `https://github.com/owner/repo`
- GitHub shorthand: `owner/repo`
- A **local repo path** (e.g. `D:/path/to/checkout`) or `file://` URL — handy for analyzing private code without a remote.

## Tech stack

- **Backend:** Java 17 + Spring Boot 3, JGit (clones without needing a system `git`).
- **Frontend:** React 18 served as a static SPA (loaded from CDN with in-browser Babel — **no Node/npm build step required**), Mermaid for diagrams, Marked for the explanation.

Because the UI needs no build step, the whole app runs with just **Java + Maven**.

## Prerequisites

- JDK 17+
- Maven 3.8+
- Internet access (to clone the target repo and to load the React/Mermaid CDN scripts in the browser)

## Run

```bash
mvn clean package
java -jar target/arch-helper-1.0.0.jar
```

Then open <http://localhost:8080>, paste a repo URL (e.g. `https://github.com/spring-projects/spring-petclinic` or the shorthand `spring-projects/spring-petclinic`), optionally a branch, and click **Analyze**.

During development you can also run:

```bash
mvn spring-boot:run
```

## API

`POST /api/analyze`

```json
{ "repoUrl": "https://github.com/owner/repo", "branch": "main" }
```

`branch` is optional. The response contains `architecture`, `flows`, `dbUsage`, `outputs` and any `warnings`. The cloned repo is created in a temp directory and deleted automatically after each analysis.

## How it works

```
clone (JGit, shallow) → scan text files → analyzers → JSON → React UI
                                   ├─ ArchitectureAnalyzer
                                   ├─ FlowAnalyzer  (JavaParser → Mermaid)
                                   ├─ DbAnalyzer    (SQL / JPA / MyBatis / procs)
                                   └─ OutputAnalyzer
```

`JavaParser` is a lightweight, regex-based extractor (stereotype, injected fields, methods + bodies, HTTP mappings) — not a full AST — which keeps the tool dependency-light while handling typical Spring layouts.

## Configuration

In `src/main/resources/application.properties`:

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `analysis.clone-timeout-seconds` | `120` | Git clone timeout |
| `analysis.max-files` | `20000` | Max files scanned |
| `analysis.max-file-size-bytes` | `2000000` | Skip files larger than this |

## Limitations

- Sequence-diagram tracing currently targets Java (Spring components and Servlets/JSP). Other languages get architecture, DB and (where applicable) SQL detection but not call-flow diagrams.
- Heuristic parsing may miss dynamically-built SQL or unconventional structures.
- Private repositories over HTTPS would need credentials (not wired in this version).
