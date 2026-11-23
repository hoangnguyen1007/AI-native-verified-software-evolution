package com.evolution.analysis.javaparser;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.Context;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.logic.MethodResolutionCapability;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.core.resolution.MethodUsageResolutionCapability;
import com.github.javaparser.symbolsolver.core.resolution.SymbolResolutionCapability;
import java.util.*;

/**
 * In SOURCE_RECOVERY mode, resolves unambiguous single-type explicit imports (and Lombok annotations)
 * that are not present in project sources, reactor sources, JDK platform, or supplied dependency JARs.
 */
final class ExplicitImportTypeSolver implements TypeSolver {
    private static final Set<String> LOMBOK_TYPES = Set.of(
            "lombok.Data", "lombok.Value", "lombok.Builder", "lombok.Getter", "lombok.Setter",
            "lombok.With", "lombok.ToString", "lombok.EqualsAndHashCode",
            "lombok.NoArgsConstructor", "lombok.RequiredArgsConstructor", "lombok.AllArgsConstructor",
            "lombok.NonNull", "lombok.AccessLevel", "lombok.CustomLog", "lombok.SneakyThrows", "lombok.Synchronized", "lombok.Cleanup",
            "lombok.experimental.Accessors", "lombok.experimental.UtilityClass", "lombok.experimental.NonFinal",
            "lombok.experimental.SuperBuilder", "lombok.experimental.FieldDefaults", "lombok.experimental.Wither",
            "lombok.extern.slf4j.Slf4j", "lombok.extern.slf4j.XSlf4j",
            "lombok.extern.log4j.Log4j2", "lombok.extern.log4j.Log4j",
            "lombok.extern.java.Log", "lombok.extern.apachecommons.CommonsLog",
            "lombok.extern.jbosslog.JBossLog", "lombok.extern.flogger.Flogger");

    private static final Set<String> JPA_TYPES = Set.of(
            "Entity", "Table", "Id", "GeneratedValue", "GenerationType", "Column", "JoinColumn", "JoinColumns",
            "JoinTable", "OneToMany", "ManyToOne", "OneToOne", "ManyToMany", "MappedSuperclass", "Embeddable",
            "Embedded", "EmbeddedId", "Transient", "Version", "Enumerated", "EnumType", "Temporal", "TemporalType",
            "Lob", "Basic", "Access", "AccessType", "OrderBy", "OrderColumn", "ElementCollection", "CollectionTable",
            "MapKey", "MapKeyColumn", "MapKeyEnumerated", "NamedQuery", "NamedQueries", "NamedNativeQuery",
            "NamedEntityGraph", "EntityListeners", "PrePersist", "PostPersist", "PreUpdate", "PostUpdate",
            "PreRemove", "PostRemove", "PostLoad", "Converter", "Convert", "Inheritance", "InheritanceType",
            "DiscriminatorColumn", "DiscriminatorValue", "SequenceGenerator", "TableGenerator", "UniqueConstraint",
            "Index", "ForeignKey", "ConstraintMode", "CascadeType", "FetchType", "PersistenceContext",
            "PersistenceUnit", "EntityManager", "EntityManagerFactory", "Query", "TypedQuery", "PersistenceException",
            "NoResultException", "NonUniqueResultException", "OptimisticLockException", "PessimisticLockException",
            "LockModeType", "FlushModeType", "AttributeConverter");

    private static final Set<String> VALIDATION_TYPES = Set.of(
            "Valid", "Constraint", "Payload", "ReportAsSingleViolation", "OverridesAttribute", "GroupSequence",
            "Validator", "ValidatorFactory", "Validation", "ConstraintViolation", "ConstraintViolationException",
            "ConstraintValidator", "ConstraintValidatorContext");

    private static final Set<String> VALIDATION_CONSTRAINT_TYPES = Set.of(
            "NotNull", "Null", "NotEmpty", "NotBlank", "Size", "Min", "Max", "DecimalMin", "DecimalMax",
            "Digits", "Pattern", "Email", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
            "Past", "PastOrPresent", "Future", "FutureOrPresent", "AssertTrue", "AssertFalse");

    private static final Set<String> JAVAX_COMMON_ANNOTATIONS = Set.of(
            "PostConstruct", "PreDestroy", "Resource", "Resources", "Nullable", "Nonnull", "Generated", "Priority");

    private static final Set<String> INJECT_TYPES = Set.of(
            "Inject", "Named", "Qualifier", "Scope", "Singleton", "Provider");

    private static final Set<String> SERVLET_HTTP_TYPES = Set.of(
            "HttpServlet", "HttpServletRequest", "HttpServletResponse", "HttpSession", "Cookie", "Part");

    private static final Set<String> JAXRS_TYPES = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "Path", "Produces", "Consumes",
            "PathParam", "QueryParam", "HeaderParam", "CookieParam", "FormParam", "BeanParam",
            "DefaultValue", "ApplicationPath", "WebApplicationException");

    private static final Map<String, Set<String>> WELL_KNOWN_WILDCARD_TYPES = Map.ofEntries(
            Map.entry("jakarta.persistence", JPA_TYPES),
            Map.entry("javax.persistence", JPA_TYPES),
            Map.entry("jakarta.annotation", JAVAX_COMMON_ANNOTATIONS),
            Map.entry("javax.annotation", JAVAX_COMMON_ANNOTATIONS),
            Map.entry("jakarta.inject", INJECT_TYPES),
            Map.entry("javax.inject", INJECT_TYPES),
            Map.entry("jakarta.servlet.http", SERVLET_HTTP_TYPES),
            Map.entry("javax.servlet.http", SERVLET_HTTP_TYPES),
            Map.entry("jakarta.ws.rs", JAXRS_TYPES),
            Map.entry("javax.ws.rs", JAXRS_TYPES),
            Map.entry("org.springframework.web.bind.annotation", Set.of(
                    "RestController", "ControllerAdvice", "RestControllerAdvice", "RequestMapping",
                    "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
                    "RequestParam", "PathVariable", "RequestBody", "ResponseBody", "RequestHeader",
                    "CookieValue", "ModelAttribute", "SessionAttribute", "SessionAttributes",
                    "RequestAttribute", "RequestPart", "MatrixVariable", "ExceptionHandler",
                    "InitBinder", "ResponseStatus", "CrossOrigin", "RequestMethod", "ValueConstants", "BindParam")),
            Map.entry("org.springframework.http", Set.of(
                    "ResponseEntity", "HttpStatus", "HttpStatusCode", "HttpHeaders", "HttpMethod",
                    "HttpEntity", "MediaType", "RequestEntity", "ProblemDetail", "CacheControl")),
            Map.entry("org.springframework.web.client", Set.of(
                    "RestTemplate", "RestClient", "HttpClientErrorException", "HttpServerErrorException", "RestClientException")),
            Map.entry("org.springframework.web.reactive.function.client", Set.of(
                    "WebClient", "ClientResponse", "WebClientResponseException", "ExchangeFilterFunction")),
            Map.entry("org.springframework.cloud.openfeign", Set.of(
                    "FeignClient", "EnableFeignClients")),
            Map.entry("org.springframework.kafka.annotation", Set.of(
                    "KafkaListener", "KafkaHandler", "EnableKafka", "TopicPartition", "PartitionOffset")),
            Map.entry("org.springframework.amqp.rabbit.annotation", Set.of(
                    "RabbitListener", "RabbitHandler", "EnableRabbit", "QueueBinding", "Queue", "Exchange")),
            Map.entry("org.springframework.stereotype", Set.of(
                    "Component", "Service", "Repository", "Controller", "Indexed")),
            Map.entry("org.springframework.beans.factory.annotation", Set.of(
                    "Autowired", "Qualifier", "Value", "Lookup", "Configurable")),
            Map.entry("org.springframework.context.annotation", Set.of(
                    "Configuration", "Bean", "Import", "Profile", "Scope", "Lazy", "Primary", "DependsOn",
                    "ComponentScan", "PropertySource", "PropertySources", "Conditional", "Role",
                    "Description", "ImportResource", "ScopedProxyMode", "FilterType")),
            Map.entry("org.springframework.context.event", Set.of(
                    "EventListener", "ApplicationContextEvent", "ContextRefreshedEvent")),
            Map.entry("org.springframework.core.annotation", Set.of(
                    "Order", "AliasFor", "AnnotationUtils", "MergedAnnotations")),
            Map.entry("org.springframework.boot.autoconfigure", Set.of(
                    "SpringBootApplication", "EnableAutoConfiguration", "AutoConfiguration",
                    "AutoConfigureAfter", "AutoConfigureBefore", "AutoConfigureOrder", "SpringBootConfiguration")),
            Map.entry("org.springframework.boot.context.properties", Set.of(
                    "ConfigurationProperties", "EnableConfigurationProperties", "ConfigurationPropertiesScan",
                    "ConstructorBinding", "NestedConfigurationProperty")),
            Map.entry("org.springframework.transaction.annotation", Set.of(
                    "Transactional", "EnableTransactionManagement", "Propagation", "Isolation",
                    "TransactionalEventListener")),
            Map.entry("org.springframework.data.annotation", Set.of(
                    "Id", "Transient", "Version", "CreatedBy", "CreatedDate", "LastModifiedBy",
                    "LastModifiedDate", "PersistenceCreator", "Immutable", "ReadOnlyProperty", "Reference", "TypeAlias")),
            Map.entry("org.springframework.data.domain", Set.of(
                    "Page", "Pageable", "PageRequest", "PageImpl", "Sort", "Slice", "SliceImpl", "Example", "ExampleMatcher")),
            Map.entry("org.springframework.data.jpa.repository", Set.of(
                    "JpaRepository", "JpaSpecificationExecutor", "Query", "Modifying", "EntityGraph",
                    "Lock", "QueryHints", "Temporal", "Meta")),
            Map.entry("org.springframework.data.repository", Set.of(
                    "Repository", "CrudRepository", "PagingAndSortingRepository", "ListCrudRepository",
                    "ListPagingAndSortingRepository", "NoRepositoryBean", "Param", "QueryDefinition")),
            Map.entry("org.springframework.cache.annotation", Set.of(
                    "Cacheable", "CacheEvict", "CachePut", "Caching", "CacheConfig", "EnableCaching")),
            Map.entry("org.springframework.scheduling.annotation", Set.of(
                    "Async", "Scheduled", "Schedules", "EnableAsync", "EnableScheduling")),
            Map.entry("org.springframework.security.access.prepost", Set.of(
                    "PreAuthorize", "PostAuthorize", "PreFilter", "PostFilter")),
            Map.entry("jakarta.validation", VALIDATION_TYPES),
            Map.entry("javax.validation", VALIDATION_TYPES),
            Map.entry("jakarta.validation.constraints", VALIDATION_CONSTRAINT_TYPES),
            Map.entry("javax.validation.constraints", VALIDATION_CONSTRAINT_TYPES),
            Map.entry("com.fasterxml.jackson.annotation", Set.of(
                    "JsonProperty", "JsonIgnore", "JsonIgnoreProperties", "JsonInclude", "JsonCreator", "JsonValue",
                    "JsonFormat", "JsonTypeInfo", "JsonSubTypes", "JsonAlias", "JsonAnyGetter", "JsonAnySetter",
                    "JsonBackReference", "JsonManagedReference", "JsonIdentityInfo", "JsonNaming",
                    "JsonPropertyOrder", "JsonRawValue", "JsonRootName", "JsonUnwrapped", "JsonView")),
            Map.entry("com.fasterxml.jackson.databind", Set.of(
                    "ObjectMapper", "JsonNode", "ObjectNode", "ArrayNode", "JavaType", "JsonSerializer",
                    "JsonDeserializer", "SerializerProvider", "DeserializationContext", "SerializationFeature",
                    "DeserializationFeature", "MapperFeature", "PropertyNamingStrategies", "Module")),
            Map.entry("com.fasterxml.jackson.core.type", Set.of("TypeReference")),
            Map.entry("org.slf4j", Set.of("Logger", "LoggerFactory", "MDC", "Marker", "MarkerFactory")),
            Map.entry("org.mapstruct", Set.of(
                    "Mapper", "Mapping", "Mappings", "MappingTarget", "AfterMapping", "BeforeMapping",
                    "BeanMapping", "InheritInverseConfiguration", "InheritConfiguration", "Named", "Qualifier",
                    "ValueMapping", "Condition", "Context", "NullValuePropertyMappingStrategy",
                    "NullValueCheckStrategy", "NullValueMappingStrategy", "ReportingPolicy",
                    "InjectionStrategy", "MappingConstants", "SubclassMapping", "ObjectFactory")),
            Map.entry("io.swagger.v3.oas.annotations", Set.of(
                    "Operation", "Parameter", "Parameters", "Hidden", "ExternalDocumentation", "Server", "Servers")),
            Map.entry("io.swagger.v3.oas.annotations.media", Set.of(
                    "Schema", "Content", "ArraySchema", "ExampleObject")),
            Map.entry("io.swagger.v3.oas.annotations.responses", Set.of("ApiResponse", "ApiResponses")),
            Map.entry("io.swagger.v3.oas.annotations.tags", Set.of("Tag", "Tags")),
            Map.entry("io.swagger.v3.oas.annotations.security", Set.of("SecurityRequirement", "SecurityScheme")),
            Map.entry("reactor.core.publisher", Set.of("Mono", "Flux", "Sinks", "Signal", "Hooks")),
            Map.entry("com.google.common.collect", Set.of(
                    "ImmutableList", "ImmutableMap", "ImmutableSet", "Lists", "Maps", "Sets",
                    "Multimap", "BiMap", "Table", "Range")),
            Map.entry("com.google.common.base", Set.of(
                    "Preconditions", "Strings", "MoreObjects", "Objects", "Stopwatch", "Splitter",
                    "Joiner", "Suppliers", "CaseFormat")),
            Map.entry("org.assertj.core.api", Set.of(
                    "Assertions", "SoftAssertions", "AbstractAssert", "Condition", "Tuple")),
            Map.entry("androidx.annotation", Set.of(
                    "NonNull", "Nullable", "VisibleForTesting", "UiThread", "MainThread", "WorkerThread",
                    "StringRes", "DrawableRes", "IdRes", "ColorInt", "RequiresApi", "Keep", "RestrictTo")),
            Map.entry("androidx.lifecycle", Set.of(
                    "LiveData", "MutableLiveData", "ViewModel", "AndroidViewModel",
                    "Lifecycle", "LifecycleOwner", "LifecycleObserver", "Observer")),
            Map.entry("io.micrometer.core.annotation", Set.of("Timed", "Counted")),
            Map.entry("io.grpc", Set.of(
                    "Server", "ServerBuilder", "ServerServiceDefinition", "ServerInterceptor",
                    "ServerInterceptors", "ServerCall", "ServerCallHandler", "ManagedChannel",
                    "ManagedChannelBuilder", "Channel", "ClientInterceptor", "ClientInterceptors",
                    "ClientCall", "CallOptions", "Metadata", "Status", "StatusRuntimeException",
                    "StatusException", "Context", "BindableService", "MethodDescriptor", "ServiceDescriptor",
                    "Grpc", "InsecureServerCredentials", "InsecureChannelCredentials", "ServerCredentials",
                    "ChannelCredentials")),
            Map.entry("io.grpc.stub", Set.of(
                    "StreamObserver", "AbstractStub", "AbstractAsyncStub", "AbstractBlockingStub",
                    "AbstractFutureStub", "ClientCalls", "ServerCalls", "ClientResponseObserver",
                    "ServerCallStreamObserver", "CallStreamObserver")),
            Map.entry("io.grpc.services", Set.of(
                    "HealthStatusManager", "BinlogHelper", "BinaryLogProvider", "ChannelzService",
                    "ProtoReflectionService")),
            Map.entry("io.grpc.health.v1", Set.of(
                    "HealthCheckRequest", "HealthCheckResponse", "HealthGrpc")),
            Map.entry("org.junit.jupiter.api", Set.of(
                    "Test", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll", "DisplayName",
                    "Disabled", "Nested", "Tag", "Tags", "RepeatedTest", "TestFactory",
                    "TestInstance", "TestMethodOrder", "Order", "Timeout", "TempDir", "Assertions", "Assumptions")),
            Map.entry("org.junit", Set.of(
                    "Test", "Before", "After", "BeforeClass", "AfterClass", "Ignore", "Rule", "ClassRule", "Assert", "Assume")),
            Map.entry("org.mockito", Set.of(
                    "Mock", "Spy", "InjectMocks", "Captor", "Mockito", "BDDMockito", "ArgumentCaptor", "ArgumentMatchers")),
            Map.entry("kotlin.jvm", Set.of(
                    "JvmStatic", "JvmField", "JvmOverloads", "JvmName", "JvmSynthetic",
                    "JvmWildcard", "JvmSuppressWildcards", "Throws", "Synchronized", "Volatile",
                    "Transient", "Strictfp", "JvmMultifileClass", "JvmInline", "JvmRecord",
                    "JvmDefault", "JvmDefaultWithoutCompatibility", "JvmDefaultWithCompatibility")),
            Map.entry("android.app", Set.of(
                    "Activity", "AlertDialog", "Application", "Dialog", "Notification",
                    "NotificationChannel", "NotificationManager", "PendingIntent", "ProgressDialog", "Service")),
            Map.entry("android.content", Set.of(
                    "BroadcastReceiver", "ClipData", "ClipboardManager", "ComponentName", "ContentResolver",
                    "ContentUris", "ContentValues", "Context", "ContextWrapper", "DialogInterface",
                    "Intent", "IntentFilter", "ServiceConnection", "SharedPreferences", "UriPermission")),
            Map.entry("android.content.pm", Set.of(
                    "ActivityInfo", "ApplicationInfo", "PackageInfo", "PackageManager",
                    "ProviderInfo", "ResolveInfo", "ServiceInfo", "Signature")),
            Map.entry("android.content.res", Set.of(
                    "AssetManager", "ColorStateList", "Configuration", "Resources", "TypedArray")),
            Map.entry("android.graphics", Set.of(
                    "Bitmap", "BitmapFactory", "Canvas", "Color", "Matrix",
                    "Paint", "Path", "Point", "PointF", "Rect", "RectF", "Typeface")),
            Map.entry("android.graphics.drawable", Set.of(
                    "BitmapDrawable", "ColorDrawable", "Drawable", "GradientDrawable", "StateListDrawable")),
            Map.entry("android.net", Set.of(
                    "ConnectivityManager", "Network", "NetworkCapabilities", "NetworkInfo", "Uri")),
            Map.entry("android.os", Set.of(
                    "AsyncTask", "Binder", "Build", "Bundle", "CountDownTimer", "Environment",
                    "Handler", "HandlerThread", "IBinder", "Looper", "Message",
                    "Parcel", "ParcelFileDescriptor", "Parcelable", "PersistableBundle", "Process", "ResultReceiver", "SystemClock")),
            Map.entry("android.provider", Set.of(
                    "DocumentsContract", "MediaStore", "OpenableColumns", "Settings")),
            Map.entry("android.text", Set.of(
                    "Editable", "Html", "Spannable", "SpannableString", "SpannableStringBuilder", "TextUtils", "TextWatcher")),
            Map.entry("android.util", Set.of(
                    "ArrayMap", "ArraySet", "Base64", "DisplayMetrics", "Log",
                    "LruCache", "Pair", "SparseArray", "SparseBooleanArray", "TypedValue", "Xml")),
            Map.entry("android.view", Set.of(
                    "Gravity", "KeyEvent", "LayoutInflater", "Menu", "MenuInflater", "MenuItem",
                    "MotionEvent", "Surface", "View", "ViewGroup", "Window", "WindowInsets", "WindowManager")),
            Map.entry("android.webkit", Set.of(
                    "CookieManager", "MimeTypeMap", "WebChromeClient", "WebResourceRequest",
                    "WebSettings", "WebView", "WebViewClient")),
            Map.entry("android.widget", Set.of(
                    "AdapterView", "ArrayAdapter", "Button", "CheckBox", "CompoundButton", "EditText",
                    "FrameLayout", "ImageButton", "ImageView", "LinearLayout", "ListView", "ProgressBar",
                    "RadioButton", "RadioGroup", "RelativeLayout", "ScrollView", "SearchView", "SeekBar",
                    "Spinner", "Switch", "TextView", "Toast")),
            Map.entry("androidx.appcompat.app", Set.of(
                    "ActionBar", "AlertDialog", "AppCompatActivity", "AppCompatDelegate")),
            Map.entry("androidx.core.app", Set.of(
                    "ActivityCompat", "NotificationCompat", "NotificationManagerCompat", "ShareCompat", "TaskStackBuilder")),
            Map.entry("androidx.core.content", Set.of("ContextCompat", "FileProvider")),
            Map.entry("androidx.fragment.app", Set.of(
                    "DialogFragment", "Fragment", "FragmentActivity", "FragmentManager", "FragmentTransaction")),
            Map.entry("androidx.recyclerview.widget", Set.of(
                    "DividerItemDecoration", "GridLayoutManager", "LinearLayoutManager", "RecyclerView")),
            Map.entry("androidx.preference", Set.of(
                    "CheckBoxPreference", "EditTextPreference", "ListPreference", "Preference",
                    "PreferenceCategory", "PreferenceFragmentCompat", "PreferenceManager", "SwitchPreferenceCompat")),
            Map.entry("androidx.room", Set.of(
                    "ColumnInfo", "Dao", "Database", "Delete", "Embedded", "Entity", "ForeignKey", "Ignore",
                    "Index", "Insert", "PrimaryKey", "Query", "Relation", "Room", "RoomDatabase",
                    "Transaction", "TypeConverter", "TypeConverters", "Update")),
            Map.entry("androidx.work", Set.of(
                    "Constraints", "CoroutineWorker", "Data", "ExistingPeriodicWorkPolicy", "ExistingWorkPolicy",
                    "ListenableWorker", "OneTimeWorkRequest", "PeriodicWorkRequest", "WorkInfo",
                    "WorkManager", "WorkRequest", "Worker", "WorkerParameters")),
            Map.entry("com.google.android.material.dialog", Set.of("MaterialAlertDialogBuilder")),
            Map.entry("com.google.android.material.snackbar", Set.of("Snackbar")),
            Map.entry("com.google.android.material.tabs", Set.of("TabLayout")),
            Map.entry("com.google.android.material.textfield", Set.of("TextInputEditText", "TextInputLayout")),
            Map.entry("okhttp3", Set.of(
                    "Cache", "Call", "Callback", "ConnectionSpec", "Cookie", "CookieJar", "FormBody", "Headers",
                    "HttpUrl", "Interceptor", "MediaType", "MultipartBody", "OkHttpClient",
                    "Request", "RequestBody", "Response", "ResponseBody", "WebSocket", "WebSocketListener")),
            Map.entry("retrofit2", Set.of("Call", "Callback", "Converter", "Response", "Retrofit")),
            Map.entry("retrofit2.http", Set.of(
                    "Body", "DELETE", "Field", "FieldMap", "FormUrlEncoded", "GET", "HEAD", "Header", "HeaderMap",
                    "Headers", "HTTP", "Multipart", "PATCH", "POST", "PUT", "Part", "PartMap", "Path", "Query",
                    "QueryMap", "Streaming", "Url")),
            Map.entry("com.google.gson", Set.of(
                    "Gson", "GsonBuilder", "JsonArray", "JsonElement", "JsonObject", "JsonParser", "JsonPrimitive", "TypeAdapter")),
            Map.entry("com.google.gson.annotations", Set.of("Expose", "SerializedName", "Since", "Until")),
            Map.entry("org.jetbrains.annotations", Set.of("NotNull", "Nullable", "NonNls", "Contract", "Unmodifiable")),
            Map.entry("org.apache.commons.lang3", Set.of(
                    "StringUtils", "ArrayUtils", "Validate", "ObjectUtils", "BooleanUtils", "RandomStringUtils")),
            Map.entry("org.apache.commons.io", Set.of("FileUtils", "IOUtils", "FilenameUtils")),
            Map.entry("kotlinx.coroutines", Set.of(
                    "CoroutineDispatcher", "CoroutineScope", "CoroutineStart", "Deferred", "Dispatchers",
                    "GlobalScope", "Job", "MainScope", "SupervisorJob", "WithContext")),
            Map.entry("io.reactivex.rxjava3.core", Set.of(
                    "Completable", "Flowable", "Maybe", "Observable", "Scheduler", "Single")));

    private static final Set<String> KOTLIN_IMPLICIT_JVM_ANNOTATIONS =
            WELL_KNOWN_WILDCARD_TYPES.getOrDefault("kotlin.jvm", Set.of());

    private static final Set<String> SYNTHETIC_MISSING_PACKAGES = Set.of("other", "missing", "unavailable");

    private record PendingWildcardCandidate(
            String unitPackage,
            String simpleName,
            Set<String> wildcardPackages,
            boolean isAnnotation) {}

    private TypeSolver parent;
    private final Map<String, ExplicitImportTypeDeclaration> declarations = new TreeMap<>();
    private final Map<String, Integer> observedMaxTypeArgs = new HashMap<>();
    private final Set<String> observedInterfaceHints = new HashSet<>();
    private final Set<String> observedAnnotationHints = new HashSet<>();
    private final Map<String, String> observedSimpleToFqn = new LinkedHashMap<>();
    private final Set<String> observedQualifiedTypes = new LinkedHashSet<>();
    private final List<PendingWildcardCandidate> pendingWildcardCandidates = new ArrayList<>();
    private final Set<String> explicitlyImportedFqns = new HashSet<>();

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        this.parent = parent;
    }

    boolean isRecoveredType(String qualifiedName) {
        return knowsType(qualifiedName);
    }

    boolean knowsType(String qualifiedName) {
        if (declarations.containsKey(qualifiedName)) return true;
        int dot = qualifiedName.lastIndexOf('.');
        while (dot > 0) {
            String prefix = qualifiedName.substring(0, dot);
            if (declarations.containsKey(prefix)) return true;
            dot = prefix.lastIndexOf('.');
        }
        return false;
    }

    Optional<ExplicitImportTypeDeclaration> resolveStaticImportOwner(CompilationUnit unit, String memberName) {
        if (unit == null) return Optional.empty();
        for (ImportDeclaration imp : unit.getImports()) {
            if (!imp.isStatic() || imp.isAsterisk()) continue;
            String fqn = imp.getNameAsString();
            int dot = fqn.lastIndexOf('.');
            if (dot <= 0) continue;
            String member = fqn.substring(dot + 1);
            if (!member.equals(memberName)) continue;
            String ownerFqn = fqn.substring(0, dot);
            var ref = tryToSolveType(ownerFqn);
            if (ref.isSolved() && ref.getCorrespondingDeclaration() instanceof ExplicitImportTypeDeclaration decl) {
                return Optional.of(decl);
            }
        }
        return Optional.empty();
    }

    void observeUnit(CompilationUnit unit) {
        String unitPkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Set<String> unitLocalNames = new HashSet<>();
        for (com.github.javaparser.ast.body.TypeDeclaration<?> td : unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class)) {
            unitLocalNames.add(td.getNameAsString());
        }
        for (com.github.javaparser.ast.type.TypeParameter tp : unit.findAll(com.github.javaparser.ast.type.TypeParameter.class)) {
            unitLocalNames.add(tp.getNameAsString());
        }
        Map<String, String> localSimpleToFqn = new HashMap<>();
        Set<String> wildcardPackages = new LinkedHashSet<>();
        for (ImportDeclaration imp : unit.getImports()) {
            if (imp.isAsterisk()) {
                if (!imp.isStatic()) {
                    String pkg = imp.getNameAsString();
                    if (!SYNTHETIC_MISSING_PACKAGES.contains(pkg)) {
                        wildcardPackages.add(pkg);
                    }
                }
                continue;
            }
            String fqn = imp.getNameAsString();
            int dot = fqn.lastIndexOf('.');
            if (dot <= 0) continue;
            String pkg = fqn.substring(0, dot);
            if (SYNTHETIC_MISSING_PACKAGES.contains(pkg) || SYNTHETIC_MISSING_PACKAGES.contains(pkg.split("\\.")[0])) continue;
            if (imp.isStatic()) {
                String ownerType = pkg;
                int ownerDot = ownerType.lastIndexOf('.');
                if (ownerDot <= 0) continue;
                String simple = ownerType.substring(ownerDot + 1);
                localSimpleToFqn.put(simple, ownerType);
                observedSimpleToFqn.put(simple, ownerType);
            } else {
                String simple = fqn.substring(dot + 1);
                localSimpleToFqn.put(simple, fqn);
                observedSimpleToFqn.put(simple, fqn);
            }
        }
        for (ClassOrInterfaceType type : unit.findAll(ClassOrInterfaceType.class)) {
            String simple = type.getNameAsString();
            String scoped = type.getNameWithScope();
            if (type.getScope().isEmpty()) {
                if (!localSimpleToFqn.containsKey(simple)) {
                    Optional<String> knownWildcard = resolveWildcardSimple(simple, wildcardPackages);
                    if (knownWildcard.isPresent()) {
                        String wFqn = knownWildcard.orElseThrow();
                        localSimpleToFqn.put(simple, wFqn);
                        observedSimpleToFqn.put(simple, wFqn);
                    } else if (!wildcardPackages.isEmpty()
                            && simple.length() > 1
                            && Character.isUpperCase(simple.codePointAt(0))
                            && !unitLocalNames.contains(simple)) {
                        pendingWildcardCandidates.add(new PendingWildcardCandidate(
                                unitPkg, simple, Set.copyOf(wildcardPackages), false));
                    }
                }
            } else {
                extractInlineTopLevelFqn(scoped, localSimpleToFqn).ifPresent(observedQualifiedTypes::add);
            }
            String fqn = type.getScope().isPresent()
                    ? resolveScopedFqn(scoped, localSimpleToFqn)
                    : localSimpleToFqn.getOrDefault(simple, scoped);
            if (type.getTypeArguments().isPresent()) {
                int count = type.getTypeArguments().orElseThrow().size();
                observedMaxTypeArgs.merge(fqn, count, Math::max);
                observedMaxTypeArgs.merge(simple, count, Math::max);
            }
        }
        for (ClassOrInterfaceDeclaration decl : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            for (ClassOrInterfaceType impl : decl.getImplementedTypes()) {
                String scoped = impl.getNameWithScope();
                String fqn = impl.getScope().isPresent()
                        ? resolveScopedFqn(scoped, localSimpleToFqn)
                        : localSimpleToFqn.getOrDefault(impl.getNameAsString(), scoped);
                observedInterfaceHints.add(fqn);
                observedInterfaceHints.add(impl.getNameAsString());
            }
        }
        for (AnnotationExpr ann : unit.findAll(AnnotationExpr.class)) {
            String rawName = ann.getNameAsString();
            if (rawName.contains(".")) {
                extractInlineTopLevelFqn(rawName, localSimpleToFqn).ifPresent(observedQualifiedTypes::add);
                String fqn = resolveScopedFqn(rawName, localSimpleToFqn);
                observedAnnotationHints.add(fqn);
            } else {
                if (!localSimpleToFqn.containsKey(rawName)) {
                    Optional<String> knownWildcard = resolveWildcardSimple(rawName, wildcardPackages);
                    if (knownWildcard.isPresent()) {
                        String wFqn = knownWildcard.orElseThrow();
                        localSimpleToFqn.put(rawName, wFqn);
                        observedSimpleToFqn.put(rawName, wFqn);
                    } else if (KOTLIN_IMPLICIT_JVM_ANNOTATIONS.contains(rawName)) {
                        String ktFqn = "kotlin.jvm." + rawName;
                        localSimpleToFqn.put(rawName, ktFqn);
                        observedSimpleToFqn.put(rawName, ktFqn);
                    } else if (!wildcardPackages.isEmpty()
                            && rawName.length() > 1
                            && Character.isUpperCase(rawName.codePointAt(0))
                            && !unitLocalNames.contains(rawName)) {
                        pendingWildcardCandidates.add(new PendingWildcardCandidate(
                                unitPkg, rawName, Set.copyOf(wildcardPackages), true));
                    }
                }
                String fqn = localSimpleToFqn.getOrDefault(rawName, rawName);
                observedAnnotationHints.add(fqn);
                observedAnnotationHints.add(rawName);
            }
        }
    }

    private static Optional<String> resolveWildcardSimple(String simple, Set<String> wildcardPackages) {
        if (wildcardPackages.isEmpty() || simple.isEmpty() || !Character.isUpperCase(simple.codePointAt(0))) {
            return Optional.empty();
        }
        for (String pkg : wildcardPackages) {
            Set<String> known = WELL_KNOWN_WILDCARD_TYPES.get(pkg);
            if (known != null && known.contains(simple)) {
                return Optional.of(pkg + "." + simple);
            }
        }
        return Optional.empty();
    }

    private static String resolveScopedFqn(String scopedName, Map<String, String> localSimpleToFqn) {
        int firstDot = scopedName.indexOf('.');
        if (firstDot <= 0) {
            return localSimpleToFqn.getOrDefault(scopedName, scopedName);
        }
        String firstSeg = scopedName.substring(0, firstDot);
        String importedFirst = localSimpleToFqn.get(firstSeg);
        if (importedFirst != null) {
            return importedFirst + scopedName.substring(firstDot);
        }
        return scopedName;
    }

    private static Optional<String> extractInlineTopLevelFqn(String scopedName, Map<String, String> localSimpleToFqn) {
        String[] parts = scopedName.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        String first = parts[0];
        if (first.isEmpty()
                || localSimpleToFqn.containsKey(first)
                || SYNTHETIC_MISSING_PACKAGES.contains(first)
                || first.equals("java")
                || !Character.isLowerCase(first.codePointAt(0))) {
            return Optional.empty();
        }
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i];
            if (!seg.isEmpty() && Character.isUpperCase(seg.codePointAt(0))) {
                String pkg = String.join(".", Arrays.copyOfRange(parts, 0, i));
                return Optional.of(pkg + "." + seg);
            }
        }
        return Optional.empty();
    }

    void materialize(
            Set<String> sourceTypeNames,
            ResolutionEnvironment environment,
            boolean includeLombokAnnotations) {
        Set<String> sourcePackages = new HashSet<>();
        for (String srcType : sourceTypeNames) {
            int dot = srcType.lastIndexOf('.');
            if (dot > 0) {
                sourcePackages.add(srcType.substring(0, dot));
            }
        }
        for (PendingWildcardCandidate cand : pendingWildcardCandidates) {
            String simple = cand.simpleName();
            if (observedSimpleToFqn.containsKey(simple)) continue;
            if (environment.isKnownInPrimarySolvers("java.lang." + simple)) continue;
            if (!cand.unitPackage().isEmpty()) {
                String samePkgFqn = cand.unitPackage() + "." + simple;
                if (sourceTypeNames.contains(samePkgFqn) || environment.isKnownInPrimarySolvers(samePkgFqn)) {
                    continue;
                }
            }
            boolean alreadyKnownInWildcard = false;
            List<String> externalWildcards = new ArrayList<>();
            List<String> annotationDomainWildcards = new ArrayList<>();
            List<String> nonAnnotationWildcards = new ArrayList<>();
            for (String wPkg : cand.wildcardPackages()) {
                String candidateFqn = wPkg + "." + simple;
                if (sourceTypeNames.contains(candidateFqn) || environment.isKnownInPrimarySolvers(candidateFqn)) {
                    alreadyKnownInWildcard = true;
                    break;
                }
                if (wPkg.indexOf('.') <= 0
                        || wPkg.startsWith("java.")
                        || SYNTHETIC_MISSING_PACKAGES.contains(wPkg)
                        || SYNTHETIC_MISSING_PACKAGES.contains(wPkg.substring(0, wPkg.indexOf('.')))
                        || sourcePackages.contains(wPkg)) {
                    continue;
                }
                externalWildcards.add(wPkg);
                boolean isAnnotationPkg = wPkg.contains("annotation")
                        || wPkg.contains("constraints")
                        || wPkg.contains("stereotype")
                        || wPkg.startsWith("lombok");
                if (isAnnotationPkg || wPkg.contains("persistence") || wPkg.contains("validation") || wPkg.contains("bind")) {
                    annotationDomainWildcards.add(wPkg);
                }
                if (!isAnnotationPkg && !WELL_KNOWN_WILDCARD_TYPES.containsKey(wPkg)) {
                    nonAnnotationWildcards.add(wPkg);
                }
            }
            if (alreadyKnownInWildcard) continue;
            String selectedPkg = null;
            if (externalWildcards.size() == 1) {
                selectedPkg = externalWildcards.getFirst();
            } else if (cand.isAnnotation() && annotationDomainWildcards.size() == 1) {
                selectedPkg = annotationDomainWildcards.getFirst();
            } else if (!cand.isAnnotation() && nonAnnotationWildcards.size() == 1) {
                selectedPkg = nonAnnotationWildcards.getFirst();
            }
            if (selectedPkg != null) {
                String fqn = selectedPkg + "." + simple;
                observedSimpleToFqn.put(simple, fqn);
                if (cand.isAnnotation() || observedAnnotationHints.contains(simple)) {
                    observedAnnotationHints.add(fqn);
                }
                if (observedInterfaceHints.contains(simple)) {
                    observedInterfaceHints.add(fqn);
                }
            }
        }
        for (Map.Entry<String, String> entry : observedSimpleToFqn.entrySet()) {
            String simple = entry.getKey();
            String fqn = entry.getValue();
            if (sourceTypeNames.contains(fqn) || environment.isKnownInPrimarySolvers(fqn)) {
                continue;
            }
            explicitlyImportedFqns.add(fqn);
            int typeParamCount = Math.max(
                    observedMaxTypeArgs.getOrDefault(fqn, 0),
                    observedMaxTypeArgs.getOrDefault(simple, 0));
            registerDeclaration(
                    fqn,
                    typeParamCount,
                    observedInterfaceHints.contains(fqn),
                    observedAnnotationHints.contains(fqn));
        }
        for (String fqn : observedQualifiedTypes) {
            if (sourceTypeNames.contains(fqn) || environment.isKnownInPrimarySolvers(fqn)) {
                continue;
            }
            int dot = fqn.lastIndexOf('.');
            String simple = dot > 0 ? fqn.substring(dot + 1) : fqn;
            int typeParamCount = Math.max(
                    observedMaxTypeArgs.getOrDefault(fqn, 0),
                    observedMaxTypeArgs.getOrDefault(simple, 0));
            registerDeclaration(
                    fqn,
                    typeParamCount,
                    observedInterfaceHints.contains(fqn),
                    observedAnnotationHints.contains(fqn));
        }

        if (includeLombokAnnotations) {
            for (String lombokFqn : LOMBOK_TYPES) {
                if (!sourceTypeNames.contains(lombokFqn) && !environment.isKnownInPrimarySolvers(lombokFqn)) {
                    explicitlyImportedFqns.add(lombokFqn);
                    boolean isAnn = !lombokFqn.endsWith(".AccessLevel");
                    registerDeclaration(lombokFqn, 0, false, isAnn);
                }
            }
        }
    }

    boolean isExplicitlyImported(String qualifiedName) {
        if (explicitlyImportedFqns.contains(qualifiedName)) return true;
        int dot = qualifiedName.lastIndexOf('.');
        while (dot > 0) {
            String prefix = qualifiedName.substring(0, dot);
            if (explicitlyImportedFqns.contains(prefix)) return true;
            dot = prefix.lastIndexOf('.');
        }
        return false;
    }

    private ExplicitImportTypeDeclaration registerDeclaration(
            String fqn, int typeParamCount, boolean isInterface, boolean isAnnotation) {
        return declarations.computeIfAbsent(fqn, key -> {
            int dot = key.lastIndexOf('.');
            String pkg = dot > 0 ? key.substring(0, dot) : "";
            String simple = dot > 0 ? key.substring(dot + 1) : key;
            return new ExplicitImportTypeDeclaration(pkg, simple, key, Optional.empty(), typeParamCount, isInterface, isAnnotation, this);
        });
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(
            String moduleName, String simpleTypeName) {
        return SymbolReference.unsolved();
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        ExplicitImportTypeDeclaration existing = declarations.get(name);
        if (existing != null) {
            return SymbolReference.solved(existing);
        }
        int dot = name.lastIndexOf('.');
        while (dot > 0) {
            String prefix = name.substring(0, dot);
            ExplicitImportTypeDeclaration outer = declarations.get(prefix);
            if (outer != null) {
                ExplicitImportTypeDeclaration nested = createNestedChain(outer, name.substring(dot + 1).split("\\."));
                return SymbolReference.solved(nested);
            }
            dot = prefix.lastIndexOf('.');
        }
        return SymbolReference.unsolved();
    }

    private ExplicitImportTypeDeclaration createNestedChain(ExplicitImportTypeDeclaration outer, String[] parts) {
        ExplicitImportTypeDeclaration current = outer;
        for (String part : parts) {
            String nextFqn = current.getQualifiedName() + "." + part;
            ExplicitImportTypeDeclaration parentDecl = current;
            int typeParamCount = Math.max(
                    observedMaxTypeArgs.getOrDefault(nextFqn, 0),
                    observedMaxTypeArgs.getOrDefault(part, 0));
            current = declarations.computeIfAbsent(nextFqn, key -> new ExplicitImportTypeDeclaration(
                    parentDecl.getPackageName(),
                    parentDecl.getClassName() + "." + part,
                    key,
                    Optional.of(parentDecl),
                    typeParamCount,
                    observedInterfaceHints.contains(key),
                    observedAnnotationHints.contains(key),
                    this));
        }
        return current;
    }

    static final class ExplicitImportTypeDeclaration
            implements ResolvedClassDeclaration,
                    ResolvedInterfaceDeclaration,
                    ResolvedAnnotationDeclaration,
                    MethodResolutionCapability,
                    MethodUsageResolutionCapability,
                    SymbolResolutionCapability {
        private final String packageName;
        private final String className;
        private final String simpleName;
        private final String qualifiedName;
        private final Optional<ResolvedReferenceTypeDeclaration> container;
        private final List<ResolvedTypeParameterDeclaration> typeParameters;
        private final List<ResolvedConstructorDeclaration> constructors;
        private final boolean interfaceHint;
        private final boolean annotationHint;
        private final TypeSolver solver;

        ExplicitImportTypeDeclaration(
                String packageName,
                String className,
                String qualifiedName,
                Optional<ResolvedReferenceTypeDeclaration> container,
                int typeParamCount,
                boolean interfaceHint,
                boolean annotationHint,
                TypeSolver solver) {
            this.packageName = packageName;
            this.className = className;
            int lastDot = className.lastIndexOf('.');
            this.simpleName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
            this.qualifiedName = qualifiedName;
            this.container = container;
            this.interfaceHint = interfaceHint;
            this.annotationHint = annotationHint;
            this.solver = solver;
            var params = new ArrayList<ResolvedTypeParameterDeclaration>(typeParamCount);
            for (int i = 0; i < typeParamCount; i++) {
                params.add(new SyntheticTypeParameter("T" + i, this));
            }
            this.typeParameters = List.copyOf(params);
            this.constructors = List.of(new ZeroArgConstructor(this));
        }

        boolean isExplicitlyImported() {
            return solver instanceof ExplicitImportTypeSolver s && s.isExplicitlyImported(qualifiedName);
        }

        @Override
        public String getPackageName() {
            return packageName;
        }

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public String getQualifiedName() {
            return qualifiedName;
        }

        @Override
        public String getName() {
            return simpleName;
        }

        @Override
        public String getId() {
            return qualifiedName;
        }

        @Override
        public Optional<ResolvedReferenceTypeDeclaration> containerType() {
            return container;
        }

        @Override
        public Optional<Node> toAst() {
            return Optional.empty();
        }

        @Override
        public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) {
            return List.of();
        }

        @Override
        public List<ResolvedFieldDeclaration> getAllFields() {
            return List.of();
        }

        @Override
        public Set<ResolvedMethodDeclaration> getDeclaredMethods() {
            return Set.of();
        }

        @Override
        public Set<MethodUsage> getAllMethods() {
            return Set.of();
        }

        @Override
        public Set<ResolvedReferenceTypeDeclaration> internalTypes() {
            return Set.of();
        }

        @Override
        public SymbolReference<ResolvedMethodDeclaration> solveMethod(
                String name, List<ResolvedType> argumentsTypes, boolean staticOnly) {
            return SymbolReference.unsolved();
        }

        @Override
        public Optional<MethodUsage> solveMethodAsUsage(
                String name,
                List<ResolvedType> argumentsTypes,
                Context invokationContext,
                List<ResolvedType> typeParameters) {
            return Optional.empty();
        }

        @Override
        public SymbolReference<? extends ResolvedValueDeclaration> solveSymbol(
                String name, TypeSolver typeSolver) {
            return SymbolReference.unsolved();
        }

        @Override
        public boolean isAssignableBy(ResolvedType type) {
            if (type.isNull()) return true;
            if (!type.isReferenceType()) return false;
            ResolvedReferenceType ref = type.asReferenceType();
            if (qualifiedName.equals(ref.getQualifiedName())) return true;
            try {
                for (ResolvedReferenceType ancestor : ref.getAllAncestors()) {
                    if (qualifiedName.equals(ancestor.getQualifiedName())) return true;
                }
            } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
                // Keep assignability conservative if ancestor traversal fails.
            }
            return false;
        }

        @Override
        public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) {
            if (other == null) return false;
            if (qualifiedName.equals(other.getQualifiedName())) return true;
            try {
                for (ResolvedReferenceType ancestor : other.getAllAncestors()) {
                    if (qualifiedName.equals(ancestor.getQualifiedName())) return true;
                }
            } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
                // Keep assignability conservative if ancestor traversal fails.
            }
            return false;
        }

        @Override
        public boolean canBeAssignedTo(ResolvedReferenceTypeDeclaration other) {
            if (other == null) return false;
            String otherName = other.getQualifiedName();
            return qualifiedName.equals(otherName) || "java.lang.Object".equals(otherName);
        }

        @Override
        public boolean hasDirectlyAnnotation(String qualifiedName) {
            return false;
        }

        @Override
        public boolean isFunctionalInterface() {
            return false;
        }

        @Override
        public List<ResolvedConstructorDeclaration> getConstructors() {
            return constructors;
        }

        @Override
        public AccessSpecifier accessSpecifier() {
            return AccessSpecifier.PUBLIC;
        }

        public AccessSpecifier accessLevel() {
            return AccessSpecifier.PUBLIC;
        }

        @Override
        public Optional<ResolvedReferenceType> getSuperClass() {
            if (solver != null && solver.getRoot() != null) {
                var obj = solver.getRoot().tryToSolveType("java.lang.Object");
                if (obj.isSolved()) {
                    return Optional.of(new ReferenceTypeImpl(obj.getCorrespondingDeclaration()));
                }
            }
            return Optional.empty();
        }

        @Override
        public List<ResolvedReferenceType> getInterfaces() {
            return List.of();
        }

        @Override
        public List<ResolvedReferenceType> getAllSuperClasses() {
            return getSuperClass().map(List::of).orElseGet(List::of);
        }

        @Override
        public List<ResolvedReferenceType> getAllInterfaces() {
            return List.of();
        }

        @Override
        public List<ResolvedReferenceType> getInterfacesExtended() {
            return List.of();
        }

        @Override
        public List<ResolvedAnnotationMemberDeclaration> getAnnotationMembers() {
            return List.of();
        }

        @Override
        public boolean isInheritable() {
            return false;
        }

        @Override
        public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
            return typeParameters;
        }

        @Override
        public boolean isType() {
            return true;
        }

        @Override
        public boolean isReferenceType() {
            return true;
        }

        @Override
        public boolean isClass() {
            return !interfaceHint && !annotationHint;
        }

        @Override
        public boolean isInterface() {
            return interfaceHint;
        }

        @Override
        public boolean isAnnotation() {
            return annotationHint;
        }

        @Override
        public ResolvedReferenceTypeDeclaration asReferenceType() {
            return this;
        }

        @Override
        public ResolvedTypeDeclaration asType() {
            return this;
        }

        @Override
        public ResolvedClassDeclaration asClass() {
            return this;
        }

        @Override
        public ResolvedInterfaceDeclaration asInterface() {
            return this;
        }

        @Override
        public ResolvedAnnotationDeclaration asAnnotation() {
            return this;
        }

        public Optional<Context> getContext() {
            return Optional.empty();
        }
    }

    private record SyntheticTypeParameter(String name, ExplicitImportTypeDeclaration container)
            implements ResolvedTypeParameterDeclaration {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getContainerQualifiedName() {
            return container.getQualifiedName();
        }

        @Override
        public String getContainerId() {
            return container.getId();
        }

        @Override
        public ResolvedTypeParametrizable getContainer() {
            return container;
        }

        @Override
        public List<Bound> getBounds() {
            return List.of();
        }

        @Override
        public ResolvedReferenceType object() {
            return container.getSuperClass().orElseThrow();
        }

        @Override
        public Optional<ResolvedReferenceTypeDeclaration> containerType() {
            return Optional.of(container);
        }
    }

    private record ZeroArgConstructor(ExplicitImportTypeDeclaration owner) implements ResolvedConstructorDeclaration {
        @Override
        public ResolvedReferenceTypeDeclaration declaringType() {
            return owner;
        }

        @Override
        public int getNumberOfParams() {
            return 0;
        }

        @Override
        public ResolvedParameterDeclaration getParam(int i) {
            throw new IndexOutOfBoundsException(i);
        }

        @Override
        public int getNumberOfSpecifiedExceptions() {
            return 0;
        }

        @Override
        public ResolvedType getSpecifiedException(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public AccessSpecifier accessSpecifier() {
            return AccessSpecifier.PUBLIC;
        }

        @Override
        public String getName() {
            return owner.getName();
        }

        @Override
        public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
            return List.of();
        }
    }
}
