# Contributing to nf-mapper

Thank you for your interest in contributing to nf-mapper! This document provides guidelines and instructions for contributing.

## Development Environment

### Option 1: GitHub Codespaces (Recommended)

The easiest way to get started:

1. Click **Code** → **Codespaces** → **Create codespace on main**
2. Wait 2-3 minutes for the environment to build
3. Everything is pre-configured and ready to use!

The devcontainer includes:
- Java 17 (Temurin)
- Maven 3.9
- All necessary VS Code extensions
- Pre-downloaded dependencies

### Option 2: VS Code Dev Containers (Local)

Requirements:
- [Docker Desktop](https://www.docker.com/products/docker-desktop)
- [VS Code](https://code.visualstudio.com/)
- [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers)

Steps:
1. Clone the repository
2. Open in VS Code
3. Press `F1` → **Dev Containers: Reopen in Container**

### Option 3: Local Development

Requirements:
- Java 17 or higher
- Maven 3.6 or higher
- Git

Setup:
```bash
git clone https://github.com/Skitionek/nf-mapper.git
cd nf-mapper/nf-mapper
mvn clean verify
```

## Project Structure

```
nf-mapper/
├── src/main/java/com/nfmapper/
│   ├── cli/              # CLI entry point (picocli)
│   ├── parser/           # Nextflow AST parser
│   ├── mermaid/          # Mermaid gitGraph renderer
│   └── model/            # Data model classes
├── src/test/java/com/nfmapper/
│   ├── parser/           # Parser unit tests
│   ├── mermaid/          # Renderer unit tests
│   ├── cli/              # CLI tests
│   └── snapshot/         # Snapshot tests
└── src/test/resources/fixtures/
    └── *.nf              # Test Nextflow files
```

## Development Workflow

### 1. Create a Branch

```bash
git checkout -b feature/your-feature-name
```

### 2. Make Your Changes

Follow the existing code style:
- 4 spaces for indentation
- Organize imports automatically
- Use meaningful variable and method names
- Add Javadoc for public APIs

### 3. Write Tests

Every feature or bug fix should include tests:

```java
@Test
void shouldParseProcessWithMultipleOutputs() {
    ParsedPipeline pipeline = parser.parseFile("test.nf");
    assertThat(pipeline.getProcesses()).hasSize(1);
    // ... more assertions
}
```

### 4. Run Tests Locally

```bash
cd nf-mapper
mvn test                    # Run all tests
mvn test -Dtest=ParserTest  # Run specific test class
```

### 5. Run the Full Build

```bash
mvn clean verify            # Compile, test, and verify
```

### 6. Update Snapshots (if needed)

If you modify rendering logic, update snapshot tests:

```bash
mvn test -Dtest=SnapshotTest
git diff snapshots/         # Review changes
git add snapshots/
```

### 7. Commit Your Changes

Write clear commit messages:

```
Add support for workflow parameter parsing

- Parse workflow parameters from workflow definition blocks
- Extract default values and types
- Add tests for parameter extraction
```

### 8. Push and Create a Pull Request

```bash
git push origin feature/your-feature-name
```

Then open a PR on GitHub with:
- **Title**: Clear, concise description
- **Description**: What changed and why
- **Testing**: How you tested the changes
- **Screenshots**: For visual changes (Mermaid diagrams)

## Code Style

The project follows standard Java conventions:

- **Classes**: `PascalCase`
- **Methods/variables**: `camelCase`
- **Constants**: `UPPER_SNAKE_CASE`
- **Packages**: `lowercase`

Format code automatically in VS Code:
- Save file (auto-formats on save)
- Or `Shift+Alt+F` (Format Document)

## Testing Guidelines

### Unit Tests

Test individual components in isolation:
- `ParserTest.java` - Parser logic
- `MermaidRendererTest.java` - Rendering logic
- `CliTest.java` - CLI behavior

### Snapshot Tests

Visual regression tests for Mermaid output:
- `SnapshotTest.java` generates `.md` files in `snapshots/`
- Compare diffs carefully when updating

### Test Fixtures

Add new `.nf` files to `src/test/resources/fixtures/` for:
- Real-world pipeline examples
- Edge cases
- Regression tests

## Debugging

### In VS Code (Codespaces/Dev Container)

1. Open a test file or main class
2. Set breakpoints (click left gutter)
3. Press `F5` or use the Run/Debug panel
4. Choose a debug configuration:
   - **Debug nf-mapper CLI** - Run with a sample file
   - **Debug Current Test File** - Run current test

### Command Line

```bash
mvn test -Dmaven.surefire.debug="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
```

Then attach your IDE debugger to port 5005.

## Common Tasks

### Adding a New Parser Feature

1. Update `NextflowParser.java` to extract the new information
2. Add fields to relevant model classes (`NfProcess`, `NfWorkflow`, etc.)
3. Write tests in `ParserTest.java`
4. Update `MermaidRenderer.java` if visualization is needed

### Adding a New Rendering Feature

1. Update `MermaidRenderer.java` with new logic
2. Add tests in `MermaidRendererTest.java`
3. Create test fixtures if needed
4. Run snapshot tests to verify output

### Fixing a Bug

1. Write a failing test that reproduces the bug
2. Fix the bug
3. Verify the test passes
4. Check that existing tests still pass

## Release Process

(Maintainers only)

1. Update version in `pom.xml`
2. Update `CHANGELOG.md`
3. Create and push a git tag
4. GitHub Actions builds and publishes the Docker image

## Getting Help

- **Issues**: Open an issue for bugs or feature requests
- **Discussions**: Ask questions in GitHub Discussions
- **Code Review**: Tag maintainers in PRs for review

## Code of Conduct

Be respectful, inclusive, and constructive. We're all here to learn and improve nf-mapper together.

---

Thank you for contributing! 🎉
