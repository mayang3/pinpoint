package com.nhn.pinpoint.profiler.interceptor.bci;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;


import com.nhn.pinpoint.profiler.Agent;
import com.nhn.pinpoint.profiler.interceptor.Interceptor;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaAssistByteCodeInstrumentor implements ByteCodeInstrumentor {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final boolean isInfo = logger.isInfoEnabled();
    private final boolean isDebug = logger.isDebugEnabled();

    private final NamedClassPool rootClassPool;
    // classPool의 수평적 확장이 필요할수 있음. was에 여러개의 webapp가 있을 경우 충돌방지.
    private final NamedClassPool childClassPool;

    private Agent agent;

    private final ClassLoadChecker classLoadChecker = new ClassLoadChecker();

    public JavaAssistByteCodeInstrumentor() {
        this.rootClassPool = createClassPool(null, "rootClassPool");
        this.childClassPool = new NamedClassPool(rootClassPool, "childClassPool");
    }

    public JavaAssistByteCodeInstrumentor(String[] pathNames, Agent agent) {
        this.rootClassPool = createClassPool(pathNames, "rootClassPool");
        this.childClassPool = createChildClassPool(rootClassPool, "childClassPool");
        this.agent = agent;
        // agent의 class는 rootClassPool에 넣는다.
        checkLibrary(this.getClass().getClassLoader(), this.rootClassPool, this.getClass().getName());
    }

    public Agent getAgent() {
        return agent;
    }

    public ClassPool getClassPool() {
        return this.childClassPool;
    }

    private NamedClassPool createClassPool(String[] pathNames, String classPoolName) {
        NamedClassPool classPool = new NamedClassPool(null, classPoolName);
        classPool.appendSystemPath();
        if (pathNames != null) {
            for (String path : pathNames) {
                appendClassPath(classPool, path);
            }
        }
        return classPool;
    }

    private NamedClassPool createChildClassPool(ClassPool rootClassPool, String classPoolName) {
        NamedClassPool childClassPool = new NamedClassPool(rootClassPool, classPoolName);
        childClassPool.appendSystemPath();
        childClassPool.childFirstLookup = true;
        return childClassPool;
    }


    private void appendClassPath(ClassPool classPool, String pathName) {
        try {
            classPool.appendClassPath(pathName);
        } catch (NotFoundException e) {
            if (logger.isWarnEnabled()) {
                logger.warn("appendClassPath fail. lib not found. {}", e.getMessage(), e);
            }
        }
    }

    public void checkLibrary(ClassLoader classLoader, String javassistClassName) {
        checkLibrary(classLoader, this.childClassPool, javassistClassName);
    }

    public void checkLibrary(ClassLoader classLoader, NamedClassPool classPool, String javassistClassName) {
        // TODO Util로 뽑을까?
        boolean findClass = findClass(javassistClassName, classPool);
        if (findClass) {
            if (isDebug) {
                logger.debug("checkLibrary cl:{} clPool:{}, class:{} found.", classLoader, classPool.getName(), javassistClassName);
            }
            return;
        }
        loadClassLoaderLibraries(classLoader, classPool);
    }

    @Override
    public InstrumentClass getClass(String javassistClassName) throws InstrumentException {
        try {
            CtClass cc = childClassPool.get(javassistClassName);
            return new JavaAssistClass(this, cc);
        } catch (NotFoundException e) {
            throw new InstrumentException(javassistClassName + " class not fund. Cause:" + e.getMessage(), e);
        }
    }

    @Override
    public Class<?> defineClass(ClassLoader classLoader, String defineClass, ProtectionDomain protectedDomain) throws InstrumentException {
        if (isInfo) {
            logger.info("defineClass class:{}, cl:{}", defineClass, classLoader);
        }
        try {
//            아래 classLoaderChecker가 생겼으니 classLoader 를 같이 락으로 잡아야 되지 않는가?
//            synchronized (classLoader)
            if (this.classLoadChecker.exist(classLoader, defineClass)) {
                return classLoader.loadClass(defineClass);
            } else {
                CtClass clazz = childClassPool.get(defineClass);
                defineNestedClass(clazz, classLoader, protectedDomain);
                return clazz.toClass(classLoader, protectedDomain);
            }
        } catch (NotFoundException e) {
            throw new InstrumentException(defineClass + " class not fund. Cause:" + e.getMessage(), e);
        } catch (CannotCompileException e) {
            throw new InstrumentException(defineClass + " class define fail. cl:" + classLoader + " Cause:" + e.getMessage(), e);
        } catch (ClassNotFoundException e) {
            throw new InstrumentException(defineClass + " class not fund. Cause:" + e.getMessage(), e);
        }
    }

    private void defineNestedClass(CtClass clazz, ClassLoader classLoader, ProtectionDomain protectedDomain) throws NotFoundException, CannotCompileException {
        CtClass[] nestedClasses = clazz.getNestedClasses();
        if (nestedClasses.length == 0) {
            return;
        }
        for (CtClass nested : nestedClasses) {
            // 재귀하면서 최하위부터 로드
            defineNestedClass(nested, classLoader, protectedDomain);
            if (isInfo) {
                logger.info("defineNestedClass class:{} cl:{}", nested.getName(), classLoader);
            }
            nested.toClass(classLoader, protectedDomain);
        }
    }

    public boolean findClass(String javassistClassName, ClassPool classPool) {
        // TODO 원래는 get인데. find는 ctclas를 생성하지 않아 변경. 어차피 아래서 생성하기는 함. 유효성 여부 확인
        // 필요
        URL url = classPool.find(javassistClassName);
        if (url == null) {
            return false;
        }
        return true;
    }

    @Override
    public Interceptor newInterceptor(ClassLoader classLoader, ProtectionDomain protectedDomain, String interceptorFQCN) throws InstrumentException {
        Class<?> aClass = this.defineClass(classLoader, interceptorFQCN, protectedDomain);
        try {
            return (Interceptor) aClass.newInstance();
        } catch (InstantiationException e) {
            throw new InstrumentException(aClass + " instance create fail Cause:" + e.getMessage(), e);
        } catch (IllegalAccessException e) {
            throw new InstrumentException(aClass + " instance create fail Cause:" + e.getMessage(), e);
        }
    }

    @Override
    public Interceptor newInterceptor(ClassLoader classLoader, ProtectionDomain protectedDomain, String interceptorFQCN, Object[] params, Class[] paramClazz) throws InstrumentException {
        Class<?> aClass = this.defineClass(classLoader, interceptorFQCN, protectedDomain);
        try {
//            Class<?>[] paramClass = getParamClass(params);
            Constructor<?> constructor = aClass.getConstructor(paramClazz);
            return (Interceptor) constructor.newInstance(params);
        } catch (InstantiationException e) {
            throw new InstrumentException(aClass + " instance create fail Cause:" + e.getMessage(), e);
        } catch (IllegalAccessException e) {
            throw new InstrumentException(aClass + " instance create fail Cause:" + e.getMessage(), e);
        } catch (NoSuchMethodException e) {
            throw new InstrumentException(aClass + " instance create fail Cause:" + e.getMessage(), e);
        } catch (InvocationTargetException e) {
            throw new InstrumentException(aClass + " instance create fail Cause:" + e.getMessage(), e);
        }

    }

    private Class<?>[] getParamClass(Object[] params) throws InstrumentException {
        Class<?>[] paramClass = new Class<?>[params.length];
        for (int i = 0; i < params.length; i++) {
            Object o = params[i];
            if (o == null) {
                throw new InstrumentException("params[" + i + "] is null ");
            }
            paramClass[i] = o.getClass();

        }
        return paramClass;
    }

    private void loadClassLoaderLibraries(ClassLoader classLoader, NamedClassPool classPool) {
        if (classLoader instanceof URLClassLoader) {
            URLClassLoader urlClassLoader = (URLClassLoader) classLoader;
            // classLoader가 가지고 있는 전체 리소스를 가능한 패스로 다 걸어야 됨
            // 임의의 class가 없을 경우 class의 byte code를 classpool에 적재 할 수 없음.
            URL[] urlList = urlClassLoader.getURLs();
            for (URL tempURL : urlList) {
                String filePath = tempURL.getFile();
                try {
                    classPool.appendClassPath(filePath);
                    // 만약 한개만 로딩해도 된다면. return true 할것
                    if (logger.isInfoEnabled()) {
                        logger.info("Loaded classPool:{} {} ", classPool.getName(), filePath);
                    }
                } catch (NotFoundException e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("lib load fail. path:{} cl:{} clPool:{}, Cause:{}", filePath, classLoader, classPool.getName(), e.getMessage(), e);
                    }
                }
            }
        }
    }
}
