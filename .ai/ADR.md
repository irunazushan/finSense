<adr-mockito-kotlin-nonnull-stubbing>
Issue: stubbing `classifierClient.classify(any())` with `org.mockito.ArgumentMatchers.any()` passes `null` in Kotlin and can trigger `NullPointerException` for non-null parameters, followed by `InvalidUseOfMatchersException`.

Decision:
1. Add dependency: `testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")`
2. Use matcher import: `import org.mockito.kotlin.any`
3. Stub with `doReturn(...).when(mock)...` pattern:
`doReturn(ClassifierResponse(category = "FOOD_AND_DRINKS", confidence = 0.97)).`when`(classifierClient).classify(any())`

Rationale: `doReturn().when()` does not call the real method during stubbing, avoiding Kotlin non-null matcher pitfalls.
</adr-mockito-kotlin-nonnull-stubbing>

<adr-floating-point-db-assertions>
Issue: values read from DB maps (for example `classifier_confidence`) can be represented as different numeric JVM types and should not be asserted with strict equality for doubles.

Decision:
Use tolerant numeric assertions:
`assertThat((row["classifier_confidence"] as Number).toDouble())`
`    .isCloseTo(0.93, within(0.0001))`

Rationale: this avoids brittle tests due to floating-point representation and JDBC numeric conversions.
</adr-floating-point-db-assertions>
