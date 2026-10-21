package io.github.gouz.pdfj.template;

import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.JinjavaConfig;
import com.hubspot.jinjava.el.JinjavaInterpreterResolver;
import com.hubspot.jinjava.interpret.Context;
import com.hubspot.jinjava.interpret.DynamicVariableResolver;
import com.hubspot.jinjava.interpret.InterpreterFactory;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.interpret.UnknownTokenException;
import com.hubspot.jinjava.lib.exptest.ExpTest;
import com.hubspot.jinjava.lib.filter.DefaultFilter;

import java.beans.FeatureDescriptor;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jinjava.javax.el.ELContext;
import jinjava.javax.el.ELResolver;
import jinjava.javax.el.PropertyNotFoundException;

/**
 * Strict mode, as in pdfrs: an expression that reads a variable or a key missing from the data is an
 * error, unless {@code is defined}, {@code is undefined} or {@code | default(...)} is what reads it, even
 * as {@code missing or "—"}. A {@code null} of the data is a value: falsy, printed as nothing. Jinjava's
 * own strict mode cannot tell the two apart (it refuses every expression whose value is null), so the
 * lookups count what they did not find, those three take back what they read, and the interpreter
 * refuses an expression whose count is not back to zero.
 */
final class Undefined {
    private Undefined() {}

    /** lookups of the expression being resolved that found nothing */
    private static final ThreadLocal<int[]> MISSED = ThreadLocal.withInitial(() -> new int[1]);
    /** whether the last lookup that came back null found nothing, or found a null */
    private static final ThreadLocal<boolean[]> LAST_MISSED = ThreadLocal.withInitial(() -> new boolean[1]);

    /**
     * Jinjava looks a method up as a property before calling it ({@code m.items()}, the {@code is}
     * tests): a property lookup that found nothing is taken back when the call follows.
     */
    private record Lookup(Object base, Object property, boolean lastMissed) {}

    private static final ThreadLocal<Lookup[]> UNRESOLVED = ThreadLocal.withInitial(() -> new Lookup[1]);

    private static void missed() {
        MISSED.get()[0]++;
        LAST_MISSED.get()[0] = true;
    }

    private static void missed(Object base, Object property) {
        UNRESOLVED.get()[0] = new Lookup(base, property, LAST_MISSED.get()[0]);
        missed();
    }

    private static void called(Object base, Object method) {
        Lookup[] unresolved = UNRESOLVED.get();
        Lookup lookup = unresolved[0];
        unresolved[0] = null;
        if (lookup == null || lookup.base() != base || !Objects.equals(lookup.property(), method)) return;
        MISSED.get()[0]--;
        LAST_MISSED.get()[0] = lookup.lastMissed();
    }

    private static void foundNull() {
        LAST_MISSED.get()[0] = false;
    }

    /** the value just read is missing, and the caller handles it: the lookup no longer counts */
    private static boolean takeBack(Object value) {
        boolean[] lastMissed = LAST_MISSED.get();
        if (value != null || !lastMissed[0]) return false;
        MISSED.get()[0]--;
        lastMissed[0] = false;
        return true;
    }

    /** {@code is defined} / {@code is undefined}: a null of the data is defined */
    static ExpTest test(String name, boolean defined) {
        return new ExpTest() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean evaluate(Object var, JinjavaInterpreter interpreter, Object... args) {
                return takeBack(var) != defined;
            }
        };
    }

    /** {@code | default(...)}: what it stands in for may be missing */
    static final DefaultFilter DEFAULT = new DefaultFilter() {
        @Override
        public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
            takeBack(var);
            return super.filter(var, interpreter, args, kwargs);
        }
    };

    /** the interpreters of a render: the template's, and those of its imports */
    static final InterpreterFactory INTERPRETERS = new InterpreterFactory() {
        @Override
        public JinjavaInterpreter newInstance(JinjavaInterpreter orig) {
            return new Strict(orig);
        }

        @Override
        public JinjavaInterpreter newInstance(Jinjava application, Context context, JinjavaConfig config) {
            return new Strict(application, context, config);
        }
    };

    private static final class Strict extends JinjavaInterpreter {
        Strict(JinjavaInterpreter orig) {
            super(orig);
        }

        Strict(Jinjava application, Context context, JinjavaConfig config) {
            super(application, context, config);
        }

        @Override
        public Object resolveELExpression(String expression, int lineNumber) {
            int[] missed = MISSED.get();
            int outer = missed[0];
            missed[0] = 0;
            try {
                Object value = super.resolveELExpression(expression, lineNumber);
                if (missed[0] > 0) throw new UnknownTokenException(expression, lineNumber, getPosition());
                return value;
            } finally {
                missed[0] = outer;
            }
        }
    }

    /** a variable no scope holds, called by Jinjava when a lookup comes back null */
    static DynamicVariableResolver variables(Context context) {
        return name -> {
            Context scope = JinjavaInterpreter.getCurrentMaybe().map(JinjavaInterpreter::getContext).orElse(context);
            for (Context c = scope; c != null; c = c.getParent()) {
                if (c.getScope().containsKey(name)) {
                    foundNull();
                    return null;
                }
            }
            missed();
            return null;
        };
    }

    /** the keys of a map, the items of a list and the properties of an object */
    static final ELResolver PROPERTIES = new ELResolver() {
        private final ELResolver delegate = JinjavaInterpreterResolver.DEFAULT_RESOLVER_READ_ONLY;

        @Override
        public Object getValue(ELContext context, Object base, Object property) {
            boolean absent = base instanceof Map<?, ?> map && !map.containsKey(property)
                || base instanceof List<?> list && !inBounds(list, property);
            Object value;
            try {
                value = delegate.getValue(context, base, property);
            } catch (PropertyNotFoundException e) {
                missed(base, property);
                throw e;
            }
            if (absent || base != null && !context.isPropertyResolved()) missed(base, property);
            else if (value == null) foundNull();
            return value;
        }

        private static boolean inBounds(List<?> list, Object property) {
            try {
                long i = property instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(property).trim());
                return i >= 0 && i < list.size();
            } catch (NumberFormatException e) {
                return true;
            }
        }

        @Override
        public Class<?> getType(ELContext context, Object base, Object property) {
            return delegate.getType(context, base, property);
        }

        @Override
        public void setValue(ELContext context, Object base, Object property, Object value) {
            delegate.setValue(context, base, property, value);
        }

        @Override
        public boolean isReadOnly(ELContext context, Object base, Object property) {
            return delegate.isReadOnly(context, base, property);
        }

        @Override
        public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
            return delegate.getFeatureDescriptors(context, base);
        }

        @Override
        public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return delegate.getCommonPropertyType(context, base);
        }

        @Override
        public Object invoke(ELContext context, Object base, Object method, Class<?>[] paramTypes, Object[] params) {
            called(base, method);
            return delegate.invoke(context, base, method, paramTypes, params);
        }
    };
}
