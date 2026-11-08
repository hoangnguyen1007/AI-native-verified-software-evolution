package com.evolution.analysis.javaparser;

import com.evolution.analysis.frontend.FrontendRequest;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic source-evidenced synthetic declarations for Android {@code R}, {@code BuildConfig},
 * {@code *Binding} (ViewBinding/DataBinding), {@code .aidl} interfaces, and core {@code android.*} /
 * {@code androidx.*} hierarchy types when Android SDK/AAR/AGP build outputs are absent.
 */
final class AndroidSyntheticDeclarations {
    private static final Set<String> R_CATEGORIES = Set.of(
            "id", "layout", "string", "drawable", "color", "dimen", "style", "menu",
            "xml", "raw", "attr", "bool", "integer", "array", "plurals", "anim",
            "animator", "mipmap", "fraction", "interpolator", "navigation", " styleable", "styleable", "font");

    private static final Pattern AIDL_PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");
    private static final Pattern AIDL_INTERFACE = Pattern.compile("(?m)\\binterface\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern AIDL_METHOD = Pattern.compile(
            "(?m)^\\s*(?:oneway\\s+)?([A-Za-z0-9_.<>\\[\\]]+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*;");

    private boolean androidUsed;
    private final Set<String> rPackages = new LinkedHashSet<>();
    private final Set<String> buildConfigPackages = new LinkedHashSet<>();
    private final Map<String, Set<String>> rMembersByCategory = new LinkedHashMap<>();
    private final Set<String> buildConfigFields = new LinkedHashSet<>(
            Set.of("DEBUG", "APPLICATION_ID", "BUILD_TYPE", "FLAVOR", "VERSION_CODE", "VERSION_NAME"));
    private final Map<String, Set<String>> bindingFieldsByFqn = new LinkedHashMap<>();
    private final Map<String, String> simpleToBindingFqn = new LinkedHashMap<>();
    private final Set<String> sourceAidlFqns = new LinkedHashSet<>();
    private final Map<String, Set<String>> sourceAidlZeroArgMethods = new LinkedHashMap<>();

    AndroidSyntheticDeclarations() {
        for (String cat : R_CATEGORIES) {
            rMembersByCategory.put(cat.trim(), new LinkedHashSet<>());
        }
    }

    void observeUnit(CompilationUnit unit) {
        String unitPkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        boolean unitUsesR = false;
        boolean unitUsesBuildConfig = false;
        Map<String, String> importedSimpleToFqn = new HashMap<>();

        for (ImportDeclaration imp : unit.getImports()) {
            String name = imp.getNameAsString();
            if (name.startsWith("android.")
                    || name.startsWith("androidx.")
                    || name.endsWith(".R")
                    || name.contains(".R.")
                    || name.endsWith(".BuildConfig")
                    || (name.contains(".databinding.") && name.endsWith("Binding"))) {
                androidUsed = true;
            }
            if (!imp.isStatic() && !imp.isAsterisk()) {
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    importedSimpleToFqn.put(name.substring(dot + 1), name);
                }
            }
            if (!imp.isStatic() && name.endsWith(".R")) {
                rPackages.add(name.substring(0, name.length() - 2));
            } else if (name.contains(".R.")) {
                int idx = name.indexOf(".R.");
                rPackages.add(name.substring(0, idx));
                String rest = name.substring(idx + 3);
                int dot = rest.indexOf('.');
                if (dot > 0) {
                    String cat = rest.substring(0, dot);
                    String member = rest.substring(dot + 1);
                    rMembersByCategory.computeIfAbsent(cat, k -> new LinkedHashSet<>()).add(member);
                } else if (!rest.isEmpty()) {
                    rMembersByCategory.computeIfAbsent(rest, k -> new LinkedHashSet<>());
                }
            } else if (!imp.isStatic() && name.endsWith(".BuildConfig")) {
                buildConfigPackages.add(name.substring(0, name.length() - ".BuildConfig".length()));
            } else if (!imp.isStatic() && !imp.isAsterisk() && name.contains(".databinding.") && name.endsWith("Binding")) {
                bindingFieldsByFqn.computeIfAbsent(name, k -> new LinkedHashSet<>());
                String simple = name.substring(name.lastIndexOf('.') + 1);
                simpleToBindingFqn.putIfAbsent(simple, name);
            }
        }

        for (ClassOrInterfaceType type : unit.findAll(ClassOrInterfaceType.class)) {
            String id = type.getNameAsString();
            if (id.endsWith("Binding") || id.equals("AppCompatActivity") || id.equals("Activity")
                    || id.equals("Fragment") || id.equals("Service") || id.equals("BroadcastReceiver")
                    || id.equals("ViewModel") || id.equals("ContentProvider")) {
                androidUsed = true;
            }
            if (id.endsWith("Binding") && !id.equals("ViewBinding") && id.length() > "Binding".length()) {
                String fqn = simpleToBindingFqn.get(id);
                if (fqn == null) {
                    fqn = importedSimpleToFqn.get(id);
                }
                if (fqn == null && !unitPkg.isEmpty()) {
                    fqn = unitPkg + ".databinding." + id;
                }
                if (fqn != null && fqn.contains(".databinding.")) {
                    simpleToBindingFqn.putIfAbsent(id, fqn);
                    bindingFieldsByFqn.computeIfAbsent(fqn, k -> new LinkedHashSet<>());
                }
            }
        }

        Map<String, String> varToBindingFqn = new HashMap<>();
        Map<String, String> varToAidlFqn = new HashMap<>();

        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("asInterface") || call.getScope().isEmpty()) {
                continue;
            }
            String scope = call.getScope().orElseThrow().toString();
            if (!scope.endsWith(".Stub")) {
                continue;
            }
            androidUsed = true;
            String prefix = scope.substring(0, scope.length() - ".Stub".length());
            String fqn = prefix.contains(".")
                    ? prefix
                    : importedSimpleToFqn.getOrDefault(prefix, unitPkg.isEmpty() ? prefix : unitPkg + "." + prefix);
            sourceAidlFqns.add(fqn);
        }

        for (var field : unit.findAll(com.github.javaparser.ast.body.FieldDeclaration.class)) {
            String tName = field.getElementType().asString();
            String bFqn = resolveBindingFqn(tName, simpleToBindingFqn);
            if (bFqn != null) {
                for (var v : field.getVariables()) {
                    varToBindingFqn.put(v.getNameAsString(), bFqn);
                }
            }
            String aFqn = importedSimpleToFqn.getOrDefault(tName, unitPkg.isEmpty() ? tName : unitPkg + "." + tName);
            if (sourceAidlFqns.contains(aFqn)) {
                for (var v : field.getVariables()) {
                    varToAidlFqn.put(v.getNameAsString(), aFqn);
                }
            }
        }
        for (var v : unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
            String tName = v.getType().asString();
            String bFqn = resolveBindingFqn(tName, simpleToBindingFqn);
            if (bFqn != null) {
                varToBindingFqn.put(v.getNameAsString(), bFqn);
            } else if (v.getInitializer().isPresent() && v.getInitializer().orElseThrow() instanceof MethodCallExpr call) {
                if ((call.getNameAsString().equals("inflate") || call.getNameAsString().equals("bind"))
                        && call.getScope().isPresent()) {
                    String scopeFqn = resolveBindingFqn(call.getScope().orElseThrow().toString(), simpleToBindingFqn);
                    if (scopeFqn != null) {
                        varToBindingFqn.put(v.getNameAsString(), scopeFqn);
                    }
                }
            }
            String aFqn = importedSimpleToFqn.getOrDefault(tName, unitPkg.isEmpty() ? tName : unitPkg + "." + tName);
            if (sourceAidlFqns.contains(aFqn)) {
                varToAidlFqn.put(v.getNameAsString(), aFqn);
            }
        }

        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            if (call.getScope().isPresent() && call.getArguments().isEmpty()) {
                String scope = call.getScope().orElseThrow().toString();
                if (scope.startsWith("this.")) {
                    scope = scope.substring("this.".length());
                }
                String aFqn = varToAidlFqn.get(scope);
                if (aFqn != null && isValidJavaIdentifier(call.getNameAsString())) {
                    sourceAidlZeroArgMethods.computeIfAbsent(aFqn, k -> new LinkedHashSet<>()).add(call.getNameAsString());
                }
            }
        }

        for (FieldAccessExpr fa : unit.findAll(FieldAccessExpr.class)) {
            String scope = fa.getScope().toString();
            if (scope.equals("R") || scope.startsWith("R.") || scope.endsWith(".R")
                    || scope.contains(".R.") || scope.equals("BuildConfig") || scope.endsWith(".BuildConfig")) {
                androidUsed = true;
            }
            if (fa.getScope() instanceof FieldAccessExpr inner) {
                String innerScope = inner.getScope().toString();
                String category = inner.getNameAsString();
                String member = fa.getNameAsString();
                if (innerScope.equals("R")) {
                    unitUsesR = true;
                    rMembersByCategory.computeIfAbsent(category, k -> new LinkedHashSet<>()).add(member);
                } else if (innerScope.endsWith(".R")) {
                    String pkg = innerScope.substring(0, innerScope.length() - 2);
                    rPackages.add(pkg);
                    rMembersByCategory.computeIfAbsent(category, k -> new LinkedHashSet<>()).add(member);
                }
            }
            if (scope.equals("BuildConfig")) {
                unitUsesBuildConfig = true;
                buildConfigFields.add(fa.getNameAsString());
            } else if (scope.endsWith(".BuildConfig")) {
                buildConfigPackages.add(scope.substring(0, scope.length() - ".BuildConfig".length()));
                buildConfigFields.add(fa.getNameAsString());
            } else if (R_CATEGORIES.contains(scope)) {
                rMembersByCategory.computeIfAbsent(scope, k -> new LinkedHashSet<>()).add(fa.getNameAsString());
            }
            String cleanScope = scope.startsWith("this.") ? scope.substring("this.".length()) : scope;
            String bFqn = varToBindingFqn.get(cleanScope);
            if (bFqn != null && isValidJavaIdentifier(fa.getNameAsString())) {
                bindingFieldsByFqn.computeIfAbsent(bFqn, k -> new LinkedHashSet<>()).add(fa.getNameAsString());
            }
        }
        if (unitUsesR && !unitPkg.isEmpty()) {
            rPackages.add(unitPkg);
        }
        if (unitUsesBuildConfig && !unitPkg.isEmpty()) {
            buildConfigPackages.add(unitPkg);
        }
    }

    void populate(
            FrontendRequest request,
            ResolutionEnvironment environment,
            JavaParser parser) {
        for (var file : request.manifest().snapshot().files()) {
            if (file.path().endsWith(".aidl") || file.path().endsWith("AndroidManifest.xml")) {
                androidUsed = true;
                break;
            }
        }
        if (!androidUsed) {
            return;
        }
        if (!environment.isKnownInPrimarySolvers("android.app.Activity")) {
            populateAndroidPlatformStubs(environment.androidPlatform, parser);
        }
        populateAidlStubs(request, environment, parser);
        populateResourceAndBuildConfigStubs(environment, parser);
        populateViewBindingStubs(environment, parser);
    }

    private static void populateAndroidPlatformStubs(MemoryTypeSolver target, JavaParser parser) {
        List<String> sources = List.of(
                """
                package android.os;
                public interface IBinder {}
                public class Binder implements IBinder {
                    public Binder() {}
                }
                public interface IInterface {
                    IBinder asBinder();
                }
                public interface Parcelable {
                    int describeContents();
                    void writeToParcel(Parcel dest, int flags);
                    interface Creator<T> {
                        T createFromParcel(Parcel source);
                        T[] newArray(int size);
                    }
                }
                public final class Parcel {
                    public void writeInt(int val) {}
                    public void writeString(String val) {}
                    public int readInt() { return 0; }
                    public String readString() { return ""; }
                }
                public final class BaseBundle {
                    public String getString(String key) { return ""; }
                    public String getString(String key, String defaultValue) { return defaultValue; }
                    public int getInt(String key) { return 0; }
                    public int getInt(String key, int defaultValue) { return defaultValue; }
                    public boolean getBoolean(String key) { return false; }
                    public boolean getBoolean(String key, boolean defaultValue) { return defaultValue; }
                    public void putString(String key, String value) {}
                    public void putInt(String key, int value) {}
                    public void putBoolean(String key, boolean value) {}
                    public boolean containsKey(String key) { return false; }
                }
                public final class Bundle extends BaseBundle implements Parcelable {
                    public static final Bundle EMPTY = new Bundle();
                    public Bundle() {}
                    public Bundle(Bundle b) {}
                    @Override public int describeContents() { return 0; }
                    @Override public void writeToParcel(Parcel dest, int flags) {}
                }
                public final class Looper {
                    public static Looper getMainLooper() { return new Looper(); }
                    public static Looper myLooper() { return new Looper(); }
                }
                public class Handler {
                    public Handler() {}
                    public Handler(Looper looper) {}
                    public final boolean post(Runnable r) { return true; }
                    public final boolean postDelayed(Runnable r, long delayMillis) { return true; }
                    public final void removeCallbacks(Runnable r) {}
                }
                """,
                """
                package android.net;
                public abstract class Uri {
                    public static Uri parse(String uriString) { return null; }
                    public String getScheme() { return ""; }
                    public String getHost() { return ""; }
                    public String getPath() { return ""; }
                    public String getQueryParameter(String key) { return ""; }
                }
                """,
                """
                package android.util;
                public final class Log {
                    public static int d(String tag, String msg) { return 0; }
                    public static int d(String tag, String msg, Throwable tr) { return 0; }
                    public static int i(String tag, String msg) { return 0; }
                    public static int i(String tag, String msg, Throwable tr) { return 0; }
                    public static int w(String tag, String msg) { return 0; }
                    public static int w(String tag, String msg, Throwable tr) { return 0; }
                    public static int w(String tag, Throwable tr) { return 0; }
                    public static int e(String tag, String msg) { return 0; }
                    public static int e(String tag, String msg, Throwable tr) { return 0; }
                    public static int v(String tag, String msg) { return 0; }
                    public static int v(String tag, String msg, Throwable tr) { return 0; }
                }
                """,
                """
                package android.content.res;
                public class Resources {
                    public String getString(int id) { return ""; }
                    public String getString(int id, Object... formatArgs) { return ""; }
                    public int getColor(int id) { return 0; }
                    public int getDimensionPixelSize(int id) { return 0; }
                }
                """,
                """
                package android.content;
                import android.content.res.Resources;
                import android.net.Uri;
                import android.os.Bundle;
                import android.os.IBinder;
                public interface SharedPreferences {
                    String getString(String key, String defValue);
                    int getInt(String key, int defValue);
                    long getLong(String key, long defValue);
                    float getFloat(String key, float defValue);
                    boolean getBoolean(String key, boolean defValue);
                    boolean contains(String key);
                    Editor edit();
                    interface Editor {
                        Editor putString(String key, String value);
                        Editor putInt(String key, int value);
                        Editor putLong(String key, long value);
                        Editor putFloat(String key, float value);
                        Editor putBoolean(String key, boolean value);
                        Editor remove(String key);
                        Editor clear();
                        boolean commit();
                        void apply();
                    }
                }
                public class Intent {
                    public static final String ACTION_VIEW = "android.intent.action.VIEW";
                    public static final String ACTION_MAIN = "android.intent.action.MAIN";
                    public static final String ACTION_SEND = "android.intent.action.SEND";
                    public Intent() {}
                    public Intent(String action) {}
                    public Intent(String action, Uri uri) {}
                    public Intent(Context packageContext, Class<?> cls) {}
                    public Intent putExtra(String name, String value) { return this; }
                    public Intent putExtra(String name, int value) { return this; }
                    public Intent putExtra(String name, long value) { return this; }
                    public Intent putExtra(String name, boolean value) { return this; }
                    public Intent putExtra(String name, Bundle value) { return this; }
                    public String getStringExtra(String name) { return ""; }
                    public int getIntExtra(String name, int defaultValue) { return defaultValue; }
                    public long getLongExtra(String name, long defaultValue) { return defaultValue; }
                    public boolean getBooleanExtra(String name, boolean defaultValue) { return defaultValue; }
                    public Bundle getExtras() { return Bundle.EMPTY; }
                    public String getAction() { return ""; }
                    public Uri getData() { return null; }
                    public Intent setData(Uri data) { return this; }
                    public Intent setAction(String action) { return this; }
                    public Intent addFlags(int flags) { return this; }
                    public Intent setFlags(int flags) { return this; }
                }
                public abstract class Context {
                    public static final int MODE_PRIVATE = 0;
                    public Context getApplicationContext() { return this; }
                    public Resources getResources() { return new Resources(); }
                    public final String getString(int resId) { return ""; }
                    public final String getString(int resId, Object... formatArgs) { return ""; }
                    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
                    public Object getSystemService(String name) { return null; }
                    public <T> T getSystemService(Class<T> serviceClass) { return null; }
                    public void startActivity(Intent intent) {}
                    public void startActivity(Intent intent, Bundle options) {}
                    public ComponentName startService(Intent service) { return null; }
                    public boolean stopService(Intent name) { return false; }
                    public void sendBroadcast(Intent intent) {}
                    public String getPackageName() { return ""; }
                }
                public class ComponentName {
                    public ComponentName(String pkg, String cls) {}
                    public ComponentName(Context pkg, Class<?> cls) {}
                }
                public class ContextWrapper extends Context {
                    public ContextWrapper(Context base) {}
                    public Context getBaseContext() { return this; }
                }
                public abstract class BroadcastReceiver {
                    public abstract void onReceive(Context context, Intent intent);
                }
                public abstract class ContentProvider {
                    public boolean onCreate() { return true; }
                    public final Context getContext() { return null; }
                }
                """,
                """
                package android.view;
                import android.content.Context;
                public interface Menu {
                    MenuItem findItem(int id);
                    MenuItem add(int groupId, int itemId, int order, int titleRes);
                    MenuItem add(CharSequence title);
                }
                public interface MenuItem {
                    int getItemId();
                    MenuItem setVisible(boolean visible);
                    MenuItem setEnabled(boolean enabled);
                    MenuItem setTitle(CharSequence title);
                    MenuItem setTitle(int title);
                }
                public class MenuInflater {
                    public void inflate(int menuRes, Menu menu) {}
                }
                public class View {
                    public static final int VISIBLE = 0;
                    public static final int INVISIBLE = 4;
                    public static final int GONE = 8;
                    public View(Context context) {}
                    public final <T extends View> T findViewById(int id) { return null; }
                    public Context getContext() { return null; }
                    public int getId() { return 0; }
                    public void setId(int id) {}
                    public void setVisibility(int visibility) {}
                    public int getVisibility() { return VISIBLE; }
                    public void setEnabled(boolean enabled) {}
                    public boolean isEnabled() { return true; }
                    public void setOnClickListener(OnClickListener l) {}
                    public interface OnClickListener {
                        void onClick(View v);
                    }
                }
                public abstract class ViewGroup extends View {
                    public ViewGroup(Context context) { super(context); }
                    public void addView(View child) {}
                    public void removeView(View view) {}
                    public void removeAllViews() {}
                    public int getChildCount() { return 0; }
                    public View getChildAt(int index) { return null; }
                }
                public abstract class LayoutInflater {
                    public static LayoutInflater from(Context context) { return null; }
                    public View inflate(int resource, ViewGroup root) { return null; }
                    public View inflate(int resource, ViewGroup root, boolean attachToRoot) { return null; }
                }
                """,
                """
                package android.widget;
                import android.content.Context;
                import android.view.View;
                import android.view.ViewGroup;
                public class TextView extends View {
                    public TextView(Context context) { super(context); }
                    public void setText(CharSequence text) {}
                    public void setText(int resid) {}
                    public CharSequence getText() { return ""; }
                }
                public class Button extends TextView {
                    public Button(Context context) { super(context); }
                }
                public class EditText extends TextView {
                    public EditText(Context context) { super(context); }
                }
                public class ImageView extends View {
                    public ImageView(Context context) { super(context); }
                    public void setImageResource(int resId) {}
                }
                public class LinearLayout extends ViewGroup {
                    public LinearLayout(Context context) { super(context); }
                }
                public class FrameLayout extends ViewGroup {
                    public FrameLayout(Context context) { super(context); }
                }
                public class RelativeLayout extends ViewGroup {
                    public RelativeLayout(Context context) { super(context); }
                }
                public class Toast {
                    public static final int LENGTH_SHORT = 0;
                    public static final int LENGTH_LONG = 1;
                    public static Toast makeText(Context context, CharSequence text, int duration) { return new Toast(); }
                    public static Toast makeText(Context context, int resId, int duration) { return new Toast(); }
                    public void show() {}
                }
                """,
                """
                package android.app;
                import android.content.Context;
                import android.content.ContextWrapper;
                import android.content.Intent;
                import android.os.Bundle;
                import android.os.IBinder;
                import android.view.LayoutInflater;
                import android.view.Menu;
                import android.view.MenuInflater;
                import android.view.MenuItem;
                import android.view.View;
                public class Application extends ContextWrapper {
                    public Application() { super(null); }
                    public void onCreate() {}
                }
                public abstract class Service extends ContextWrapper {
                    public Service() { super(null); }
                    public void onCreate() {}
                    public int onStartCommand(Intent intent, int flags, int startId) { return 0; }
                    public void onDestroy() {}
                    public abstract IBinder onBind(Intent intent);
                    public final void stopSelf() {}
                }
                public class Activity extends ContextWrapper {
                    public static final int RESULT_OK = -1;
                    public static final int RESULT_CANCELED = 0;
                    public Activity() { super(null); }
                    protected void onCreate(Bundle savedInstanceState) {}
                    protected void onStart() {}
                    protected void onResume() {}
                    protected void onPause() {}
                    protected void onStop() {}
                    protected void onDestroy() {}
                    protected void onActivityResult(int requestCode, int resultCode, Intent data) {}
                    public void setContentView(int layoutResID) {}
                    public void setContentView(View view) {}
                    public <T extends View> T findViewById(int id) { return null; }
                    public Intent getIntent() { return new Intent(); }
                    public void setIntent(Intent newIntent) {}
                    public final void setResult(int resultCode) {}
                    public final void setResult(int resultCode, Intent data) {}
                    public void finish() {}
                    public LayoutInflater getLayoutInflater() { return null; }
                    public MenuInflater getMenuInflater() { return new MenuInflater(); }
                    public boolean onCreateOptionsMenu(Menu menu) { return true; }
                    public boolean onOptionsItemSelected(MenuItem item) { return false; }
                    public final void runOnUiThread(Runnable action) {}
                }
                """,
                """
                package androidx.fragment.app;
                import android.app.Activity;
                import android.content.Context;
                import android.content.Intent;
                import android.os.Bundle;
                import android.view.LayoutInflater;
                import android.view.View;
                import android.view.ViewGroup;
                public abstract class FragmentManager {
                    public abstract FragmentTransaction beginTransaction();
                    public Fragment findFragmentById(int id) { return null; }
                    public Fragment findFragmentByTag(String tag) { return null; }
                }
                public abstract class FragmentTransaction {
                    public FragmentTransaction add(int containerViewId, Fragment fragment) { return this; }
                    public FragmentTransaction add(int containerViewId, Fragment fragment, String tag) { return this; }
                    public FragmentTransaction replace(int containerViewId, Fragment fragment) { return this; }
                    public FragmentTransaction replace(int containerViewId, Fragment fragment, String tag) { return this; }
                    public FragmentTransaction remove(Fragment fragment) { return this; }
                    public FragmentTransaction addToBackStack(String name) { return this; }
                    public abstract int commit();
                    public abstract int commitAllowingStateLoss();
                }
                public class FragmentActivity extends Activity {
                    public FragmentManager getSupportFragmentManager() { return null; }
                }
                public class Fragment {
                    public Context getContext() { return null; }
                    public final Context requireContext() { return null; }
                    public final FragmentActivity getActivity() { return null; }
                    public final FragmentActivity requireActivity() { return null; }
                    public final Bundle getArguments() { return Bundle.EMPTY; }
                    public final Bundle requireArguments() { return Bundle.EMPTY; }
                    public void setArguments(Bundle args) {}
                    public final String getString(int resId) { return ""; }
                    public final String getString(int resId, Object... formatArgs) { return ""; }
                    public View getView() { return null; }
                    public final View requireView() { return null; }
                    public final FragmentManager getParentFragmentManager() { return null; }
                    public final FragmentManager getChildFragmentManager() { return null; }
                    public void startActivity(Intent intent) {}
                    public void onCreate(Bundle savedInstanceState) {}
                    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) { return null; }
                    public void onViewCreated(View view, Bundle savedInstanceState) {}
                    public void onStart() {}
                    public void onResume() {}
                    public void onPause() {}
                    public void onStop() {}
                    public void onDestroyView() {}
                    public void onDestroy() {}
                }
                public class DialogFragment extends Fragment {
                    public void dismiss() {}
                    public void show(FragmentManager manager, String tag) {}
                }
                """,
                """
                package androidx.appcompat.app;
                import androidx.fragment.app.FragmentActivity;
                public abstract class ActionBar {
                    public abstract void setTitle(CharSequence title);
                    public abstract void setTitle(int resId);
                    public abstract void setDisplayHomeAsUpEnabled(boolean showHomeAsUp);
                    public abstract void show();
                    public abstract void hide();
                }
                public class AppCompatActivity extends FragmentActivity {
                    public ActionBar getSupportActionBar() { return null; }
                    public void setSupportActionBar(androidx.appcompat.widget.Toolbar toolbar) {}
                }
                """,
                """
                package androidx.appcompat.widget;
                import android.content.Context;
                import android.view.ViewGroup;
                public class Toolbar extends ViewGroup {
                    public Toolbar(Context context) { super(context); }
                    public void setTitle(CharSequence title) {}
                    public void setTitle(int resId) {}
                }
                """,
                """
                package androidx.lifecycle;
                public abstract class ViewModel {
                    protected void onCleared() {}
                }
                public interface LifecycleOwner {}
                public abstract class LiveData<T> {
                    public T getValue() { return null; }
                }
                public class MutableLiveData<T> extends LiveData<T> {
                    public MutableLiveData() {}
                    public MutableLiveData(T value) {}
                    public void setValue(T value) {}
                    public void postValue(T value) {}
                }
                """,
                """
                package androidx.viewbinding;
                import android.view.View;
                public interface ViewBinding {
                    View getRoot();
                }
                """,
                """
                package androidx.recyclerview.widget;
                import android.content.Context;
                import android.view.View;
                import android.view.ViewGroup;
                public class RecyclerView extends ViewGroup {
                    public RecyclerView(Context context) { super(context); }
                    public void setAdapter(Adapter<?> adapter) {}
                    public void setLayoutManager(LayoutManager layout) {}
                    public abstract static class ViewHolder {
                        public final View itemView;
                        public ViewHolder(View itemView) { this.itemView = itemView; }
                        public final int getAdapterPosition() { return 0; }
                        public final int getBindingAdapterPosition() { return 0; }
                    }
                    public abstract static class Adapter<VH extends ViewHolder> {
                        public abstract VH onCreateViewHolder(ViewGroup parent, int viewType);
                        public abstract void onBindViewHolder(VH holder, int position);
                        public abstract int getItemCount();
                        public final void notifyDataSetChanged() {}
                        public final void notifyItemChanged(int position) {}
                        public final void notifyItemInserted(int position) {}
                        public final void notifyItemRemoved(int position) {}
                    }
                    public abstract static class LayoutManager {}
                }
                public class LinearLayoutManager extends RecyclerView.LayoutManager {
                    public LinearLayoutManager(Context context) {}
                    public LinearLayoutManager(Context context, int orientation, boolean reverseLayout) {}
                }
                """);
        for (String source : sources) {
            parseAndIndex(source, target, parser, null);
        }
    }

    private void populateAidlStubs(
            FrontendRequest request,
            ResolutionEnvironment environment,
            JavaParser parser) {
        if (request.sources().isEmpty()) {
            return;
        }
        Set<String> synthesizedAidlFqns = new HashSet<>();
        for (var file : request.manifest().snapshot().files()) {
            if (!file.path().endsWith(".aidl")) {
                continue;
            }
            String normalized = file.path().replace('\\', '/');
            int aidlIdx = normalized.indexOf("/aidl/");
            if (aidlIdx >= 0) {
                String rel = normalized.substring(aidlIdx + "/aidl/".length(), normalized.length() - ".aidl".length());
                String fqn = rel.replace('/', '.');
                if (!fqn.isEmpty()) {
                    sourceAidlFqns.add(fqn);
                }
            }
        }
        for (String fqn : sourceAidlFqns) {
            if (synthesizedAidlFqns.contains(fqn) || environment.isKnownInPrimarySolvers(fqn)) {
                continue;
            }
            int dot = fqn.lastIndexOf('.');
            String pkg = dot > 0 ? fqn.substring(0, dot) : "";
            String ifaceName = dot > 0 ? fqn.substring(dot + 1) : fqn;
            StringBuilder java = new StringBuilder();
            if (!pkg.isEmpty()) {
                java.append("package ").append(pkg).append(";\n");
            }
            java.append("public interface ").append(ifaceName).append(" extends android.os.IInterface {\n");
            for (String m : sourceAidlZeroArgMethods.getOrDefault(fqn, Set.of())) {
                if (!m.equals("asBinder")) {
                    java.append("    String ").append(m).append("();\n");
                }
            }
            java.append("    abstract class Stub extends android.os.Binder implements ").append(ifaceName).append(" {\n");
            java.append("        public Stub() {}\n");
            java.append("        public static ").append(ifaceName).append(" asInterface(android.os.IBinder obj) { return null; }\n");
            java.append("        @Override public android.os.IBinder asBinder() { return this; }\n");
            java.append("    }\n");
            java.append("}\n");
            parseAndIndex(java.toString(), environment.androidProject, parser, environment);
            synthesizedAidlFqns.add(fqn);
        }
    }

    private void populateResourceAndBuildConfigStubs(
            ResolutionEnvironment environment,
            JavaParser parser) {
        for (String pkg : rPackages) {
            String rFqn = pkg.isEmpty() ? "R" : pkg + ".R";
            if (environment.isKnownInPrimarySolvers(rFqn)) {
                continue;
            }
            StringBuilder src = new StringBuilder();
            if (!pkg.isEmpty()) {
                src.append("package ").append(pkg).append(";\n");
            }
            src.append("public final class R {\n");
            for (var entry : rMembersByCategory.entrySet()) {
                String category = entry.getKey();
                if (! isValidJavaIdentifier(category)) {
                    continue;
                }
                src.append("    public static final class ").append(category).append(" {\n");
                int idVal = 0x7f010001;
                for (String member : entry.getValue()) {
                    if (!isValidJavaIdentifier(member)) {
                        continue;
                    }
                    if (category.equals("styleable")) {
                        src.append("        public static final int[] ").append(member).append(" = new int[0];\n");
                    } else {
                        src.append("        public static final int ").append(member).append(" = ").append(idVal++).append(";\n");
                    }
                }
                src.append("    }\n");
            }
            src.append("}\n");
            parseAndIndex(src.toString(), environment.androidProject, parser, environment);
        }

        for (String pkg : buildConfigPackages) {
            String bcFqn = pkg.isEmpty() ? "BuildConfig" : pkg + ".BuildConfig";
            if (environment.isKnownInPrimarySolvers(bcFqn)) {
                continue;
            }
            StringBuilder src = new StringBuilder();
            if (!pkg.isEmpty()) {
                src.append("package ").append(pkg).append(";\n");
            }
            src.append("public final class BuildConfig {\n");
            src.append("    public static final boolean DEBUG = true;\n");
            src.append("    public static final String APPLICATION_ID = \"").append(pkg).append("\";\n");
            src.append("    public static final String BUILD_TYPE = \"debug\";\n");
            src.append("    public static final String FLAVOR = \"\";\n");
            src.append("    public static final int VERSION_CODE = 1;\n");
            src.append("    public static final String VERSION_NAME = \"1.0\";\n");
            for (String field : buildConfigFields) {
                if (Set.of("DEBUG", "APPLICATION_ID", "BUILD_TYPE", "FLAVOR", "VERSION_CODE", "VERSION_NAME").contains(field)
                        || !isValidJavaIdentifier(field)) {
                    continue;
                }
                if (field.startsWith("IS_") || field.startsWith("HAS_") || field.startsWith("ENABLE_")) {
                    src.append("    public static final boolean ").append(field).append(" = false;\n");
                } else if (field.endsWith("_CODE") || field.endsWith("_PORT") || field.endsWith("_TIMEOUT")) {
                    src.append("    public static final int ").append(field).append(" = 0;\n");
                } else {
                    src.append("    public static final String ").append(field).append(" = \"\";\n");
                }
            }
            src.append("}\n");
            parseAndIndex(src.toString(), environment.androidProject, parser, environment);
        }
    }

    private void populateViewBindingStubs(
            ResolutionEnvironment environment,
            JavaParser parser) {
        if (bindingFieldsByFqn.isEmpty()) {
            return;
        }
        for (var entry : bindingFieldsByFqn.entrySet()) {
            String fqn = entry.getKey();
            if (environment.isKnownInPrimarySolvers(fqn)) {
                continue;
            }
            int dot = fqn.lastIndexOf('.');
            String pkg = dot > 0 ? fqn.substring(0, dot) : "";
            String simple = dot > 0 ? fqn.substring(dot + 1) : fqn;
            StringBuilder src = new StringBuilder();
            if (!pkg.isEmpty()) {
                src.append("package ").append(pkg).append(";\n");
            }
            src.append("public final class ").append(simple).append(" implements androidx.viewbinding.ViewBinding {\n");
            src.append("    public final android.view.View rootView;\n");
            for (String field : entry.getValue()) {
                if (!field.equals("rootView") && isValidJavaIdentifier(field)) {
                    String viewType = inferViewType(field);
                    src.append("    public final ").append(viewType).append(" ").append(field).append(";\n");
                }
            }
            src.append("    private ").append(simple).append("(android.view.View rootView) {\n");
            src.append("        this.rootView = rootView;\n");
            for (String field : entry.getValue()) {
                if (!field.equals("rootView") && isValidJavaIdentifier(field)) {
                    src.append("        this.").append(field).append(" = null;\n");
                }
            }
            src.append("    }\n");
            src.append("    @Override public android.view.View getRoot() { return rootView; }\n");
            src.append("    public static ").append(simple).append(" inflate(android.view.LayoutInflater inflater) { return null; }\n");
            src.append("    public static ").append(simple).append(" inflate(android.view.LayoutInflater inflater, android.view.ViewGroup parent, boolean attachToParent) { return null; }\n");
            src.append("    public static ").append(simple).append(" bind(android.view.View rootView) { return null; }\n");
            src.append("}\n");
            parseAndIndex(src.toString(), environment.androidProject, parser, environment);
        }
    }

    private static String inferViewType(String fieldName) {
        String lower = fieldName.toLowerCase(Locale.ROOT);
        if (lower.endsWith("button") || lower.startsWith("btn")) {
            return "android.widget.Button";
        }
        if (lower.endsWith("edittext") || lower.startsWith("et") || lower.endsWith("input")) {
            return "android.widget.EditText";
        }
        if (lower.endsWith("imageview") || lower.startsWith("iv") || lower.endsWith("icon") || lower.endsWith("image")) {
            return "android.widget.ImageView";
        }
        if (lower.endsWith("recyclerview") || lower.startsWith("rv")) {
            return "androidx.recyclerview.widget.RecyclerView";
        }
        if (lower.endsWith("toolbar")) {
            return "androidx.appcompat.widget.Toolbar";
        }
        if (lower.endsWith("text") || lower.endsWith("title") || lower.endsWith("label") || lower.startsWith("tv")) {
            return "android.widget.TextView";
        }
        return "android.widget.TextView";
    }

    private static String resolveBindingFqn(String typeName, Map<String, String> simpleToFqn) {
        if (simpleToFqn.containsKey(typeName)) {
            return simpleToFqn.get(typeName);
        }
        if (typeName.contains(".databinding.") && typeName.endsWith("Binding")) {
            return typeName;
        }
        return null;
    }

    private static void parseAndIndex(
            String source,
            MemoryTypeSolver solver,
            JavaParser parser,
            ResolutionEnvironment environment) {
        var parsed = parser.parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            return;
        }
        CompilationUnit cu = parsed.getResult().orElseThrow();
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            if (type.getFullyQualifiedName().isEmpty()) {
                continue;
            }
            String fqn = type.getFullyQualifiedName().orElseThrow();
            if (environment != null && environment.isKnownInPrimarySolvers(fqn)) {
                continue;
            }
            solver.addDeclaration(fqn, type.resolve());
        }
    }

    private static boolean isValidJavaIdentifier(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(candidate.charAt(0))) {
            return false;
        }
        for (int i = 1; i < candidate.length(); i++) {
            if (!Character.isJavaIdentifierPart(candidate.charAt(i))) {
                return false;
            }
        }
        return !Set.of(
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
                "interface", "long", "native", "new", "package", "private", "protected", "public",
                "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
                "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
                "null", "var", "yield", "record", "sealed", "permits").contains(candidate);
    }
}
