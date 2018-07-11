FROM maven:3.5.3-jdk-11
COPY pom.xml *.sh /
COPY src /src
RUN mvn clean install
CMD exec mvn exec:java -DentryPoint=org.sagebionetworks.WorkflowHook
