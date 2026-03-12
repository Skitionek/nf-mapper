# Development Container Configuration

This directory contains the configuration for developing nf-mapper in GitHub Codespaces or with VS Code Dev Containers.

## What's Included

### Base Environment

- **Java 17** (Eclipse Temurin) - Required by nf-mapper
- **Maven 3.9** - Build tool
- **Git** - Version control

### VS Code Extensions

- **Java Extension Pack** - Core Java development tools
- **Maven for Java** - Maven integration
- **Java Test Runner** - Run and debug JUnit tests
- **Java Debugger** - Debug Java applications
- **Language Support for Java** (Red Hat) - Enhanced Java language support
- **IntelliCode** - AI-assisted development
- **GitHub Copilot** - AI pair programmer
- **Mermaid Support** - Preview and syntax highlighting for Mermaid diagrams

### Automatic Setup

When the container starts:

1. Changes to `nf-mapper/` directory
2. Runs `mvn clean verify` to:
   - Download all dependencies
   - Compile source code
   - Run all tests
   - Generate snapshot diagrams

## Usage

### GitHub Codespaces

1. Navigate to the repository on GitHub
2. Click the green **Code** button
3. Select **Codespaces** tab
4. Click **Create codespace on main**

The development environment will be ready in 2-3 minutes.

### VS Code Dev Containers (Local)

1. Install [VS Code](https://code.visualstudio.com/)
2. Install the [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers)
3. Install [Docker Desktop](https://www.docker.com/products/docker-desktop)
4. Open the repository folder in VS Code
5. Press `F1` and select **Dev Containers: Reopen in Container**

## Verifying the Setup

After the container starts, verify everything works:

```bash
# Navigate to the Maven project
cd nf-mapper

# Run tests
mvn test

# Build the fat JAR
mvn package -DskipTests

# Run the CLI
java -jar target/nf-mapper-1.0.0.jar src/test/resources/fixtures/simple_workflow.nf
```

## Customization

To modify the development environment:

1. Edit `.devcontainer/devcontainer.json`
2. Rebuild the container:
   - **Codespaces**: Click the gear icon in the bottom-left, select **Rebuild Container**
   - **Local**: Press `F1`, select **Dev Containers: Rebuild Container**

## Troubleshooting

### Maven dependencies fail to download

```bash
cd nf-mapper
mvn dependency:purge-local-repository
mvn clean verify
```

### Java version issues

```bash
java -version  # Should show Java 17
mvn -version   # Should show Java 17 being used
```

### Extensions not loading

1. Open the Extensions view (`Ctrl+Shift+X`)
2. Check if extensions are installed
3. Reload the window (`Ctrl+Shift+P` → **Developer: Reload Window**)
