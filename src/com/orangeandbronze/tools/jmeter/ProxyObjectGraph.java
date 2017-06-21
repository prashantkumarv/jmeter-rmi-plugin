package com.orangeandbronze.tools.jmeter;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Array;
import java.rmi.Remote;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import static com.orangeandbronze.tools.jmeter.util.InstanceHandleFactory.buildInstanceName;


public class ProxyObjectGraph {

    private static Log log = LogFactory.getLog(ProxyObjectGraph.class);

    private InstanceRegistry instanceRegistry;
    private MethodRecorder recorder;

    private Set<Object> seenObjects = new HashSet<>();
    private Map<String, String> instances = new HashMap<>();

    public ProxyObjectGraph(final InstanceRegistry instanceRegistry,
                            final MethodRecorder recorder) {
        this.instanceRegistry = instanceRegistry;
        this.recorder = recorder;
    }

    public Map<String, String> getAndClearRemoteInstanceHandles() {
        Map<String, String> mm = new HashMap<>(instances);
        instances.clear();
        return mm;
    }

    public Object replaceRemotes(Object instance, MethodCallRecord record)
        throws Exception {
        return replaceRemotes(instance, record, "");
    }

    public Object replaceRemotes(Object instance, MethodCallRecord record,
                                 String path)
        throws Exception {

        if (instance == null) {
            return null;
        }

        Class clazz = instance.getClass();

        // Handle types
        // Primitives: Passthrough
        if (isPrimitive(clazz)) {
            return instance;
        }

        // Handle cycles by skipping already traversed objects
        if (seenObjects.contains(instance)) {
            return instance;
        }

        // If instanceof Remote, replace with proxy
        if (instance instanceof Remote) {
            record.setRemoteReturned(true);
            String instanceName = buildInstanceName((Remote) instance);
            DynamicStubProxyInvocationHandler handler =
                new DynamicStubProxyInvocationHandler(instanceRegistry,
                                                      instance,
                                                      instanceName,
                                                      recorder);
            Remote proxy = handler.buildStubProxy(false);
            instanceRegistry.registerRmiInstance(instanceName, proxy);
            instances.put(instanceName, path);
            return proxy;
        }

        // Otherwise, traverse object
        if (clazz.isArray()) {
            Object arr = traverseArrayAndReplaceRemotes(instance, record,
                                                        path);
            seenObjects.add(arr);
            return arr;
        }

        if (instance instanceof Collection) {
            Object col = traverseCollectionAndReplaceRemotes((Collection) instance,
                                                             record, path);
            seenObjects.add(col);
            return col;
        }

        BeanInfo bi = Introspector.getBeanInfo(clazz);
        if (bi != null) {
            PropertyDescriptor[] props = bi.getPropertyDescriptors();
            for (PropertyDescriptor p: props) {
                String propName = p.getName();
                if ("class".equals(p.getName())) {
                    continue;
                }

                // Ignore primitive properties
                if (isPrimitive(p.getPropertyType())) {
                    continue;
                }

                Object val = null;
                try {
                    if (p.getReadMethod() != null) {
                        val = p.getReadMethod().invoke(instance);
                    }
                    else {
                        log.warn("Can't handle non-readable property on "
                                 + instance + ":" + p.getName());
                        continue;
                    }
                }
                catch (IllegalAccessException accessEx) {
                    log.warn("Couldn't read original value for property '" + propName + "'", accessEx);
                    continue;
                }
                catch (InvocationTargetException invokEx) {
                    log.warn("Couldn't read original value for property '" + propName + "'", invokEx);
                    continue;
                }
                catch (Exception ex) {
                    log.error("Exception reading " + propName + " on " + clazz, ex);
                    throw ex;
                }


                if (val == null) {
                    continue;
                }

                if (p.getWriteMethod() != null) {
                    String getter = p.getReadMethod().getName();
                    Object propValue = replaceRemotes(val, record, path + "." + getter + "()");
                    p.getWriteMethod().invoke(instance, propValue);
                }
            }
        }
        Field[] fields = clazz.getFields();
        for (Field f: fields) {
            if (!(Modifier.isPublic(f.getModifiers())
                  && !Modifier.isStatic(f.getModifiers()))) {
                continue;
            }

            Class valType = f.getType();
            Object val = null;
            if (!f.isAccessible()) {
                log.warn("Forcing field access for " + f.toString());
                f.setAccessible(true);
            }
            try {
                val = replaceRemotes(f.get(instance),
                                     record, path + "." + f.getName());
            }
            catch (IllegalAccessException accessEx) {
                continue;
            }
            f.set(instance, val);
        }

        seenObjects.add(instance);
        return instance;
    }

    private Object traverseArrayAndReplaceRemotes(Object arrayInstance,
                                                  MethodCallRecord record,
                                                  String path)
        throws Exception {
        int arrLen = Array.getLength(arrayInstance);
        for (int i = 0; i < arrLen; i++) {
            Object o = replaceRemotes(Array.get(arrayInstance, i),
                                      record, path + "[" + i + "]");
            Array.set(arrayInstance, i, o);
        }
        return arrayInstance;
    }

    private Object traverseMapKeyValuePairs(Map m, MethodCallRecord record,
                                            String path)
        throws Exception {
        for (Object key: m.keySet()) {
            Object val = replaceRemotes(m.get(key), record,
                                        path + ".get(" + key + ")");
            m.put(key, val);
        }
        return m;
    }

    private Object traverseCollectionAndReplaceRemotes(Collection c,
                                                       MethodCallRecord record,
                                                       String path)
        throws Exception {
        if (c instanceof Map) {
            return traverseMapKeyValuePairs((Map) c, record, path);
        }

        if (c instanceof List) {
            int i = 0;
            for (ListIterator ii = ((List) c).listIterator(); ii.hasNext(); ) {
                Object o = replaceRemotes(ii.next(), record, path + ".get(" + i + ")");
                ii.set(o);
                i++;
            }
        }

        // FIXME: Handle other collection types

        return c;
    }

    private boolean isPrimitive(final Class clazz) {
        return (clazz == boolean.class
                || clazz == char.class
                || clazz == byte.class
                || clazz == short.class
                || clazz == int.class
                || clazz == long.class
                || clazz == float.class
                || clazz == double.class
                || clazz == Character.class
                || clazz == Byte.class
                || clazz == Short.class
                || clazz == Boolean.class
                || clazz == Integer.class
                || clazz == Long.class
                || clazz == Float.class
                || clazz == Double.class
                || clazz == String.class);
    }
}
