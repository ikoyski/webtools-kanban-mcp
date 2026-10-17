FROM eclipse-temurin:21-jre-jammy
EXPOSE 8080
ADD target/*.jar app.jar
ENTRYPOINT ["java", "-Dspring.profiles.active=private", "-jar", "/app.jar"]