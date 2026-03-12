# 🚀 Welcome to nf-mapper Development!

Your Codespace is ready for development. Here's what you can do:

## ✅ Environment Status

Your development environment includes:
- **Java 17** (Eclipse Temurin)
- **Maven 3.9**
- **All VS Code Java extensions**
- **Dependencies pre-downloaded**

## 🏃 Quick Start

### Run Tests
```bash
cd nf-mapper
mvn test
```

### Build the Project
```bash
cd nf-mapper
mvn package -DskipTests
```

### Try the CLI
```bash
cd nf-mapper
java -jar target/nf-mapper-1.0.0.jar src/test/resources/fixtures/simple_workflow.nf
```

### Run Specific Tests
```bash
cd nf-mapper
mvn test -Dtest=ParserTest           # Parser tests
mvn test -Dtest=MermaidRendererTest  # Renderer tests
mvn test -Dtest=SnapshotTest         # Generate snapshots
```

## 🔍 VS Code Features

### Debug the CLI
1. Open [NfMapperCli.java](nf-mapper/src/main/java/com/nfmapper/cli/NfMapperCli.java)
2. Press `F5` and select **Debug nf-mapper CLI**
3. Set breakpoints and debug!

### Run Tasks
Press `Ctrl+Shift+P` → **Tasks: Run Task** → Choose:
- `maven: verify` - Full build with tests
- `maven: test` - Run all tests
- `maven: package` - Build fat JAR
- `run: simple_workflow.nf` - Test CLI with sample file

### Test Explorer
1. Click the **Testing** icon in the left sidebar (flask icon)
2. Browse and run individual tests
3. See test results inline

## 📂 Project Structure

```
nf-mapper/src/main/java/com/nfmapper/
├── cli/              # CLI entry point (NfMapperCli.java)
├── parser/           # Nextflow AST parser (NextflowParser.java)
├── mermaid/          # Mermaid gitGraph renderer
└── model/            # Data model (NfProcess, NfWorkflow, etc.)
```

## 📚 Next Steps

1. **Read the docs**: [README.md](README.md)
2. **Contributing guide**: [CONTRIBUTING.md](CONTRIBUTING.md)
3. **Devcontainer info**: [.devcontainer/README.md](.devcontainer/README.md)

## 🐛 Debugging Tips

### View Build Output
```bash
mvn clean verify -X  # Verbose Maven output
```

### Check Java Version
```bash
java -version   # Should show Java 17
mvn -version    # Should show Maven using Java 17
```

### Clean Build
```bash
cd nf-mapper
mvn clean
rm -rf target/
mvn package
```

## 💡 Pro Tips

- **Auto-format**: Save any Java file to auto-format
- **Organize imports**: Save to auto-organize imports
- **Terminal**: Use `Ctrl+` ` to toggle terminal
- **Quick Open**: `Ctrl+P` to quickly open files
- **Command Palette**: `Ctrl+Shift+P` for all commands

## 🆘 Need Help?

- Check [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines
- Open an issue on GitHub
- Review test files for examples

Happy coding! 🎉
