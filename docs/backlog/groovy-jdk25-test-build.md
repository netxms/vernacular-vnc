---
worth: yes
where: vernacular-vnc/pom.xml
added: 2026-11-10
---
# Spock tests do not compile on JDK 25

`mvn -f vernacular-vnc/pom.xml test` fails on JDK 25. gmavenplus `compileTests` stops with "Unsupported class file major version 69", because Groovy 4.0.18 (Spock BOM `2.3-groovy-4.0`) can't read Java 25 class files. It works under JDK 17 (`JAVA_HOME=$(mise where java@17)`). The fix is to bump Groovy, and the Spock BOM if needed, to versions that support JDK 25.
