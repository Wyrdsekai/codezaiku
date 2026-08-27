---
id: java-mockito-spring-test-patterns
keys: java, mockito, spring, junit, mockmvc, springboottest, datajpatest, mock, argumentcaptor
priority: 85
---

## Mockito + Spring Boot test patterns (the Java analog of pytest mock idioms)

Spring Boot 3.5 + JUnit 5 + Mockito + Spring Test. These are the testing patterns the
email-intel-java baseline uses and that production code must respect.

### `@MockBean` vs `@Mock` — which gets you DI

```java
@WebMvcTest(EmailController.class)
class EmailControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private EmailService emailService;   // SPRING-injected mock; replaces the real bean
                                                    // — your controller will receive THIS
}
```

`@MockBean` (Spring Test annotation) replaces the bean in the application context.
`@Mock` (plain Mockito) is just a Mockito mock — does NOT participate in Spring's DI.
For controller/service tests with Spring loaded, USE `@MockBean`. For pure unit tests with no
Spring, use `@Mock` + `@InjectMocks`.

### MockMvc for controller tests — the assertion contract

```java
@Test
void test_list_emails_returns_200() throws Exception {
    when(emailService.listEmails()).thenReturn(List.of(new EmailDto(1L, "...")));
    mockMvc.perform(get("/api/emails"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1));
}
```

The test EXPECTS:
- a route registered at `/api/emails` (your controller's `@GetMapping("/emails")` + class-level
  `@RequestMapping("/api")`),
- a service bean of the mocked type available to the controller,
- a response shape matching `jsonPath` (your DTO field names matter, not just types).

If the test fails with `assert 404 == 200` (the Java analog of FastAPI's route 404), the
route is not registered. Multi-file edit set: `{controller class, application class
@SpringBootApplication scanBasePackages, possibly @ComponentScan}`.

### `ArgumentCaptor` — the Java mock-as-out-param

```java
@Test
void test_classify_writes_category() {
    var captor = ArgumentCaptor.forClass(EmailEntity.class);
    emailService.classifyEmail(new EmailEntity("body"));
    verify(emailRepository).save(captor.capture());
    assertEquals("work", captor.getValue().getCategory());     // your service must CALL repository.save()
                                                                // with an entity whose category is set
}
```

The captor reads what your service PASSED TO the mock. If your code returns a tuple and never
calls `repository.save(...)`, the captor sees nothing. If it calls save with an entity whose
`.category` isn't set, the assertion fails. Same shape as the Python out-param pattern, just
mediated by Mockito.

### `@DataJpaTest` vs `@SpringBootTest`

```java
@DataJpaTest                                 // ONLY JPA layer; in-memory H2; @Repository beans only
class EmailRepositoryTest { ... }

@SpringBootTest                              // FULL application context; slow; use sparingly
class EmailIntegrationTest { ... }

@WebMvcTest(EmailController.class)           // ONLY web layer + the named controller
class EmailControllerTest { ... }
```

Use the narrowest slice that loads what the test needs. `@SpringBootTest` is the most
expensive and the most likely to fail on incomplete configuration. If the test class uses
`@WebMvcTest` and your controller depends on a service, the service MUST be `@MockBean` —
real services don't load in `@WebMvcTest`'s sliced context.

### `when(...).thenReturn(...)` — the stub contract

```java
when(emailRepository.findById(1L)).thenReturn(Optional.of(new EmailEntity(...)));
```

Your production code under test calls `repository.findById(1L)` → gets the stubbed Optional.
If your code calls `findById(42L)` or `findByUuid(...)` instead, the mock returns `Optional.empty()`
(default) and your code likely throws NoSuchElementException. The stubbed method signature
defines what shape of repo call your code MUST make.

### Mockito common pitfalls (the small-model traps)

1. **`when().thenReturn()` only stubs ONE call shape.** `findById(1L)` ≠ `findById(2L)` for the
   mock. Use `any(Long.class)` if you don't care which ID.
2. **`@MockBean` REPLACES the bean.** Your `EmailService` constructor will receive the mock
   even if a real bean exists.
3. **`mockMvc.perform(get("..."))` exact path matters.** `/api/emails` vs `/api/emails/`
   trailing slash matters in some Spring versions; match the test exactly.
4. **Verify the controller calls the service.** Test asserts on `verify(emailService).listEmails()`
   — if your controller method body bypasses the service (returns hardcoded data), test fails.
5. **JSON deserialization is Jackson-driven.** DTO field names = JSON keys (or `@JsonProperty`
   override). Test's `jsonPath("$.id")` reads the JSON key, not the Java field name if they differ.
