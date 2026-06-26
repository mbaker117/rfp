---
name: feedback-java-version
description: Always use Java 21 (Corretto 21) for Maven commands — shell defaults to Java 8
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 9cee62d9-e564-41c1-8a84-de2804b490e9
---

Always prefix `mvn` commands with `JAVA_HOME=/Users/mobaker/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home`.

**Why:** The shell's active JVM is Corretto 8 (`java -version` returns 1.8.0_482). The project compiles to class file version 65.0 (Java 21). Running `mvn test` without setting `JAVA_HOME` causes Surefire to fail with "has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version only recognizes up to 52.0".

**How to apply:** Every `mvn` command in this project needs the prefix. Example:
```bash
JAVA_HOME=/Users/mobaker/Library/Java/JavaVirtualMachines/corretto-21.0.10/Contents/Home mvn test
```
