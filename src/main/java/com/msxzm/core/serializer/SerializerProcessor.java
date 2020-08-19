package com.msxzm.core.serializer;

import com.google.auto.service.AutoService;
import com.msxzm.base.serializer.Serializable;
import com.msxzm.base.serializer.SerializerField;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;
import java.util.*;
import java.util.function.Consumer;

/**
 * 序列化类处理器
 * @author zenghongming
 * @date 2019/12/29 17:27
 */
@AutoService(Processor.class)
public class SerializerProcessor extends AbstractProcessor {
    /** IOException */
    private static final String IO_EXCEPTION = "java.io.IOException";
    /** Entry */
    private static final String MAP_ENTRY = "java.util.Map.Entry";
    /** Iterator */
    private static final String ITERATOR = "java.util.Iterator";
    /** List的实例化类 */
    private static final String LIST_IMPL = "java.util.ArrayList";
    /** Set的实例化类 */
    private static final String SET_IMPL = "java.util.HashSet";
    /** Queue的实例化类 */
    private static final String QUEUE_IMPL = "java.util.ArrayDeque";
    /** Map的实例化类 */
    private static final String MAP_IMPL = "java.util.HashMap";

    /** 元素后缀 */
    private static final String ELEMENT = "Element";

    /** 局部变量修饰符 */
    private static final int LOCAL_VARIABLE_MODIFIERS = 0;
    /** 代码段修饰符(注意是0，不是default) */
    private static final int BLOCK_MODIFIERS = 0;

    /** 编译信息输出 */
    private Messager messager;
    /** 抽象语法树 */
    private JavacTrees trees;
    /** 抽象语法树构造 */
    private TreeMaker treeMaker;
    /** 类型 */
    private Types types;
    /** 名字 */
    private Names names;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
        this.trees = JavacTrees.instance(processingEnv);
        this.treeMaker = TreeMaker.instance(context);
        this.messager = processingEnv.getMessager();
        this.names = Names.instance(context);
        this.types = processingEnv.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<? extends Element> serialElement = roundEnv.getElementsAnnotatedWith(SerializerField.class);
        if (roundEnv.processingOver() || serialElement.isEmpty()) {
            return false;
        }
        JavaSourceWrapper javaSourceWrapper = new JavaSourceWrapper();
        serialElement.forEach(element -> {
            JCTree jcVariableTree = trees.getTree(element);
            jcVariableTree.accept(new TreeTranslator() {
                @Override
                public void visitVarDef(JCVariableDecl jcVariableDecl) {
                    super.visitVarDef(jcVariableDecl);
                    // 当前字段的类元素
                    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
                    JavaClassWrapper classWrapper = javaSourceWrapper.computeIfAbsent(enclosingElement);
                    classWrapper.addVariableDecl(element, jcVariableDecl);
                }
            });
        });
        javaSourceWrapper.forEach(classWrapper -> {
            classWrapper.forEach(variableDecl -> {
                // 增加Getter、Setter方法
            });
            // 增加必要的import
            classWrapper.addImport(treeMaker.Import(memberAccess(IO_EXCEPTION), false));
            // 增加write方法
            classWrapper.addImport(treeMaker.Import(memberAccess(SerializerBound.WRITE.getStreamClass()), false));
            classWrapper.addMethodDecl(makeReadWriteMethodDecl(classWrapper, SerializerBound.WRITE));
            // 增加read方法
            classWrapper.addImport(treeMaker.Import(memberAccess(SerializerBound.READ.getStreamClass()), false));
            classWrapper.addMethodDecl(makeReadWriteMethodDecl(classWrapper, SerializerBound.READ));
        });
        return true;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> annotations = new LinkedHashSet<>();
        annotations.add(Serializable.class.getCanonicalName());
        return annotations;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * 生成构造函数
     * @param clazz 类定义
     * @return 构造函数方法定义
     */
    private JCMethodDecl makeConstructorDecl(JCClassDecl clazz) {
        // 访问标志
        JCModifiers modifiers = treeMaker.Modifiers(Flags.PUBLIC);
        ListBuffer<JCStatement> statements = new ListBuffer<>();
        JCExpression superExec = treeMaker.Ident(names.fromString("super"));
        statements.append(treeMaker.Exec(treeMaker.Apply(List.nil(), superExec, List.nil())));
        // return this.xxx;
        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, List.nil());
        // MethodDef(mods, name, restype, typarams, params, thrown, body, defaultValue)
        return treeMaker.MethodDef(modifiers, names.fromString("<init>"), null, List.nil(), List.nil(), List.nil(), body, null);
    }

    /**
     * 生成get方法
     * @param jcVariableDecl 变量定义
     * @return get方法定义
     */
    private JCMethodDecl makeGetterMethodDecl(JCVariableDecl jcVariableDecl) {
        String fieldName = jcVariableDecl.getName().toString();
        Name methodName = names.fromString("get" + Utils.toUpperCaseFirst(fieldName));
        // 访问标志
        JCModifiers modifiers = treeMaker.Modifiers(Flags.PUBLIC);
        // 返回类型
        JCExpression resType = jcVariableDecl.vartype;
        // 方法体
        ListBuffer<JCStatement> statements = new ListBuffer<>();
        // return this.xxx;
        statements.append(treeMaker.Return(memberAccess(names.fromString("this"), jcVariableDecl.name)));
        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, statements.toList());
        // MethodDef(mods, name, restype, typarams, params, thrown, body, defaultValue)
        return treeMaker.MethodDef(modifiers, methodName, resType, List.nil(), List.nil(), List.nil(), body, null);
    }

    /**
     * 生成set方法
     * @param jcVariableDecl 变量定义
     * @return set方法定义
     */
    private JCMethodDecl makeSetterMethodDecl(JCVariableDecl jcVariableDecl) {
        String fieldName = jcVariableDecl.getName().toString();
        Name methodName = names.fromString("set" + Utils.toUpperCaseFirst(fieldName));
        // 访问标志
        JCModifiers modifiers = treeMaker.Modifiers(Flags.PUBLIC);
        // return void
        JCExpression resType = treeMaker.TypeIdent(TypeTag.VOID);
        // 参数
        JCVariableDecl param = treeMaker.VarDef(treeMaker.Modifiers(Flags.PARAMETER), jcVariableDecl.getName(), jcVariableDecl.vartype, null);
        // 方法体
        ListBuffer<JCStatement> statements = new ListBuffer<>();
        // this.xxx
        JCExpression jcFieldAccess = memberAccess(names.fromString("this"), jcVariableDecl.getName());
        // this.xxx = xxx;
        JCAssign jcAssign = treeMaker.Assign(jcFieldAccess, treeMaker.Ident(jcVariableDecl.getName()));
        // 加入方法体
        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, statements.append(treeMaker.Exec(jcAssign)).toList());
        // MethodDef(mods, name, restype, typarams, params, thrown, body, defaultValue)
        return treeMaker.MethodDef(modifiers, methodName, resType, List.nil(), List.of(param), List.nil(), body, null);
    }

    /**
     * 生成read write方法
     * @param classWrapper 类包装
     * @param bound 序列化方向 read write
     * @return 方法定义
     */
    private JCMethodDecl makeReadWriteMethodDecl(JavaClassWrapper classWrapper, SerializerBound bound) {
        ListBuffer<JCStatement> statements = new ListBuffer<>();
        // 访问标志
        JCModifiers modifiers = treeMaker.Modifiers(Flags.PUBLIC);
        // 方法名
        Name methodName = names.fromString(bound.serializerExec);
        // 参数名
        Name paramName = names.fromString(bound.paramName);
        // 参数类型
        JCExpression paramType = memberAccess(bound.getStreamClass());
        // 方法参数
        JCVariableDecl param = treeMaker.VarDef(treeMaker.Modifiers(Flags.PARAMETER), paramName, paramType, null);
        // throws IOException
        JCExpression thrown = memberAccess(IO_EXCEPTION);
        // return void
        JCExpression resType = treeMaker.TypeIdent(TypeTag.VOID);
        // 看父类是否实现了Serializable接口
        if (isSerializableAssignableFrom(classWrapper.element.asType())) {
            // super read write
            JCExpression args = treeMaker.Ident(names.fromString(bound.paramName));
            JCExpression superExec = memberAccess(names.fromString("super"), bound.serializerExec);
            statements.append(treeMaker.Exec(treeMaker.Apply(List.nil(), superExec, List.of(args))));
        }
        // read write 字段
        classWrapper.forEach(variableWrapper -> {
            // map遍历需要Entry,Iterator
            if (isMap(variableWrapper.element.asType())) {
                classWrapper.addImport(treeMaker.Import(memberAccess(MAP_ENTRY), false));
                classWrapper.addImport(treeMaker.Import(memberAccess(ITERATOR), false));
            }
            if (bound == SerializerBound.WRITE) {
                writeVariable(statements, variableWrapper.element.asType(), variableWrapper.variable);
            } else {
                readVariable(statements, variableWrapper.element.asType(), variableWrapper.variable);
            }
        });
        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, statements.toList());
        // MethodDef(mods, name, restype, typarams, params, thrown, body, defaultValue)
        return treeMaker.MethodDef(modifiers, methodName, resType, List.nil(), List.of(param), List.of(thrown), body, null);
    }

    /**
     * 写一个变量(递归一直到基础类型，或者自定义序列化对象)
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void writeVariable(ListBuffer<JCStatement> statements, TypeMirror type, JCVariableDecl variable) {
        // 数组
        if (isArray(type)) {
            writeArray(statements, (ArrayType) type, variable);
            return;
        }
        // Collection
        if (isCollection(type)) {
            writeCollection(statements, type, variable);
            return;
        }
        // Map
        if (isMap(type)) {
            writeMap(statements, type, variable);
            return;
        }
        // 基础类型
        if (isPrimitiveType(type)) {
            writePrimitive(statements, type, variable);
            return;
        }
        // 自定义序列化对象
        if (isSerializable(type)) {
            writeSerializable(statements, treeMaker.Ident(variable.getName()));
            return;
        }
        // 其他类型
        writeObject(statements, treeMaker.Ident(variable.getName()));
    }

    /**
     * 写基础类型
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void writePrimitive(ListBuffer<JCStatement> statements, TypeMirror type, JCVariableDecl variable) {
        // 包装类型得先写个null
        if (isWrapper(type)) {
            JCBinary notNull = treeMaker.Binary(Tag.NE, treeMaker.Ident(variable.name), literalNull());
            // 先写一个布尔值标记集合是否为null
            writePrimitive(statements, boolean.class, notNull);
            // 如果不为null则写值
            ListBuffer<JCStatement> thenStatements = new ListBuffer<>();
            writePrimitive(thenStatements, getPrimitiveClass(type), treeMaker.Ident(variable.getName()));
            JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
            statements.append(treeMaker.If(notNull, body, null));
        } else {
            writePrimitive(statements, getPrimitiveClass(type), treeMaker.Ident(variable.getName()));
        }
    }

    /**
     * 写基础类型
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void writePrimitive(ListBuffer<JCStatement> statements, TypeMirror type, JCExpression variable) {
        // 包装类型得先写个null
        if (isWrapper(type)) {
            JCBinary notNull = treeMaker.Binary(Tag.NE, variable, literalNull());
            // 先写一个布尔值标记集合是否为null
            writePrimitive(statements, boolean.class, notNull);
            // 如果不为null则写值
            ListBuffer<JCStatement> thenStatements = new ListBuffer<>();
            writePrimitive(thenStatements, getPrimitiveClass(type), variable);
            JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
            statements.append(treeMaker.If(notNull, body, null));
        } else {
            writePrimitive(statements, getPrimitiveClass(type), variable);
        }
    }

    /**
     * 写一个基础类型
     * @param statements 方法体stats
     * @param primitiveClass 基础类型类
     * @param variable 变量
     */
    private void writePrimitive(ListBuffer<JCStatement> statements, Class<?> primitiveClass, JCExpression variable) {
        String writeAccess = "write" + Utils.toUpperCaseFirst(primitiveClass.getSimpleName());
        JCExpression writeExec = memberAccess(names.fromString(SerializerBound.WRITE.paramName), writeAccess);
        statements.append(treeMaker.Exec(treeMaker.Apply(List.nil(), writeExec, List.of(variable))));
    }

    /**
     * 写一个数组
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void writeArray(ListBuffer<JCStatement> statements, ArrayType type, JCVariableDecl variable) {
        JCBinary notNull = treeMaker.Binary(Tag.NE, treeMaker.Ident(variable.name), literalNull());
        // 先写一个布尔值标记集合是否为null
        writePrimitive(statements, boolean.class, notNull);
        // 如果不为null则展开数组
        ListBuffer<JCStatement> thenStatements = new ListBuffer<>();

        // 先写一个长度
        JCExpression jcFieldAccess = memberAccess(variable.getName(), "length");
        writePrimitive(thenStatements, int.class, jcFieldAccess);
        // 然后for循环
        ListBuffer<JCStatement> forStatements = new ListBuffer<>();
        // 索引 name_i
        Name stepName = variable.getName().append(names.fromString("_i"));
        // int name_i = 0;
        List<JCStatement> init = List.of(localVariableDef(stepName, treeMaker.TypeIdent(TypeTag.INT), treeMaker.Literal(0)));
        // name_i < name.length
        JCBinary cond = treeMaker.Binary(Tag.LT, treeMaker.Ident(stepName), jcFieldAccess);
        // name_i++
        List<JCExpressionStatement> step = List.of(autoIncrement(stepName));
        // for循环内部
        Name elementName = variable.getName().append(names.fromString(ELEMENT));
        // 数组子元素类型
        Type elementType = type.elemtype;
        // 访问数组元素
        JCArrayAccess element = treeMaker.Indexed(treeMaker.Ident(variable.getName()), treeMaker.Ident(stepName));
        // 多维数组递归
        if (isArray(elementType)) {
            // 创建一个局部变量接一下
            JCVariableDecl elementVariable = localVariableDef(elementName, treeMaker.Type(elementType), element);
            writeArray(forStatements.append(elementVariable), (ArrayType) elementType, elementVariable);
            thenStatements.append(treeMaker.ForLoop(init, cond, step, treeMaker.Block(BLOCK_MODIFIERS, forStatements.toList())));
            JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
            statements.append(treeMaker.If(notNull, body, null));
            return;
        }
        // 基础类型或自定义序列化对象直接写，其他类型创建一个局部变量
        if (isPrimitiveType(elementType)) {
            writePrimitive(forStatements, elementType, element);
        } else if (isSerializable(elementType) && !isAbstract(elementType)) {
            writeSerializable(forStatements, element);
        } else {
            // 创建一个局部变量接一下
            JCVariableDecl elementVariable = localVariableDef(elementName, treeMaker.Type(elementType), element);
            writeVariable(forStatements.append(elementVariable), elementType, elementVariable);
        }

        thenStatements.append(treeMaker.ForLoop(init, cond, step, treeMaker.Block(BLOCK_MODIFIERS, forStatements.toList())));
        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
        statements.append(treeMaker.If(notNull, body, null));
    }

    /**
     * 写一个Collection
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void writeCollection(ListBuffer<JCStatement> statements, TypeMirror type, JCVariableDecl variable) {
        JCBinary notNull = treeMaker.Binary(Tag.NE, treeMaker.Ident(variable.name), literalNull());
        // 先写一个布尔值标记集合是否为null
        writePrimitive(statements, boolean.class, notNull);
        // 如果不为null则展开List
        ListBuffer<JCStatement> thenStatements = new ListBuffer<>();
        ListBuffer<JCStatement> forStatements = new ListBuffer<>();
        // 写长度
        JCExpressionStatement sizeExec = treeMaker.Exec(treeMaker.Apply(List.nil(), memberAccess(variable.name, "size"), List.nil()));
        writePrimitive(thenStatements, int.class, sizeExec.getExpression());
        // for展开
        Type elementType = ((Type) type).getTypeArguments().head;
        JCVariableDecl element = localVariableDef(variable.name.append(names.fromString(ELEMENT)), treeMaker.Type(elementType), literalNull());
        // write Value
        writeVariable(forStatements, elementType, element);
        thenStatements.append(treeMaker.ForeachLoop(element, treeMaker.Ident(variable.name), treeMaker.Block(BLOCK_MODIFIERS, forStatements.toList())));
        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
        statements.append(treeMaker.If(notNull, body, null));
    }

    /**
     * 写一个map
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void writeMap(ListBuffer<JCStatement> statements, TypeMirror type, JCVariableDecl variable) {
        JCBinary notNull = treeMaker.Binary(Tag.NE, treeMaker.Ident(variable.name), literalNull());
        // 先写一个布尔值标记集合是否为null
        writePrimitive(statements, boolean.class, notNull);
        // 如果不为null则展开List
        ListBuffer<JCStatement> thenStatements = new ListBuffer<>();
        ListBuffer<JCStatement> forStatements = new ListBuffer<>();
        // 写长度
        JCExpressionStatement sizeExec = treeMaker.Exec(treeMaker.Apply(List.nil(), memberAccess(variable.name, "size"), List.nil()));
        writePrimitive(thenStatements, int.class, sizeExec.getExpression());
        // 取泛型
        Type keyType = ((Type) type).getTypeArguments().head;
        Type valueType = ((Type) type).getTypeArguments().last();
        // Entry
        JCVariableDecl entryElement = localVariableDef(variable.name.append(names.fromString(ELEMENT)), memberAccess(MAP_ENTRY), null);
        JCExpressionStatement entrySetExec = treeMaker.Exec(treeMaker.Apply(List.nil(), memberAccess(variable.name, "entrySet"), List.nil()));

        // write Key
        JCExpressionStatement keyExec = treeMaker.Exec(treeMaker.Apply(List.nil(), memberAccess(entryElement.name, "getKey"), List.nil()));
        JCTypeCast castKey = treeMaker.TypeCast(keyType, keyExec.getExpression());
        writeMapArgs(forStatements, variable.name.append(names.fromString("Key")), keyType, castKey);

        // write Value
        JCExpressionStatement valueExec = treeMaker.Exec(treeMaker.Apply(List.nil(), memberAccess(entryElement.name, "getValue"), List.nil()));
        JCTypeCast castValue = treeMaker.TypeCast(valueType, valueExec.getExpression());
        writeMapArgs(forStatements, variable.name.append(names.fromString("Value")), valueType, castValue);

        thenStatements.append(treeMaker.ForeachLoop(entryElement, entrySetExec.getExpression(), treeMaker.Block(BLOCK_MODIFIERS, forStatements.toList())));
        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
        statements.append(treeMaker.If(notNull, body, null));
    }

    /**
     * 写map的参数
     * @param statements 方法体stats
     * @param name 名字
     * @param type 类型
     * @param variable 变量
     */
    private void writeMapArgs(ListBuffer<JCStatement> statements, Name name, Type type, JCExpression variable) {
        if (type.getKind().isPrimitive()) {
            writePrimitive(statements, getPrimitiveClass(type), variable);
        } else {
            JCVariableDecl elementKey = localVariableDef(name, treeMaker.Type(type), variable);
            writeVariable(statements.append(elementKey), type, elementKey);
        }
    }

    /**
     * 写可序列化的自定义对象
     * @param statements 方法体stats
     * @param variable 变量
     */
    private void writeSerializable(ListBuffer<JCStatement> statements, JCExpression variable) {
        JCBinary notNull = treeMaker.Binary(Tag.NE, variable, literalNull());
        ListBuffer<JCStatement> thenStatements = new ListBuffer<>();
        // 先写一个布尔值标记集合是否为null
        writePrimitive(statements, boolean.class, notNull);

        JCExpression writeExec = treeMaker.Select(variable, names.fromString("writeTo"));
        JCExpression args = treeMaker.Ident(names.fromString(SerializerBound.WRITE.paramName));
        thenStatements.append(treeMaker.Exec(treeMaker.Apply(List.nil(), writeExec, List.of(args))));

        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
        statements.append(treeMaker.If(notNull, body, null));
    }

    /**
     * 写一个对象
     * @param statements 方法体stats
     * @param variable 变量
     */
    private void writeObject(ListBuffer<JCStatement> statements, JCExpression variable) {
        JCExpression writeExec = memberAccess(names.fromString(SerializerBound.WRITE.paramName), "write");
        statements.append(treeMaker.Exec(treeMaker.Apply(List.nil(), writeExec, List.of(variable))));
    }

    /**
     * 读一个变量
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readVariable(ListBuffer<JCStatement> statements, TypeMirror type, JCVariableDecl variable) {
        // 数组
        if (isArray(type)) {
            readArray(statements, (ArrayType) type, variable);
            return;
        }
        // Collection
        if (isCollection(type)) {
            readCollection(statements, type, variable);
            return;
        }
        // Map
        if (isMap(type)) {
            readMap(statements, type, variable);
            return;
        }
        // 基础类型
        if (isPrimitiveType(type)) {
            readPrimitive(statements, type, variable);
            return;
        }
        // 自定义序列化对象
        if (isSerializable(type) && !isAbstract(type)) {
            readSerializable(statements, type, treeMaker.Ident(variable.getName()));
            return;
        }
        // 其他类型
        readObject(statements, treeMaker.Ident(variable.getName()));
    }

    /**
     * 读基础类型
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readPrimitive(ListBuffer<JCStatement> statements, TypeMirror type, JCVariableDecl variable) {
        // 包装类型得先读个布尔值
        if (isWrapper(type)) {
            // 先读一个标志
            JCExpression notNull = treeMaker.Exec(doReadAnPrimitive(boolean.class)).getExpression();
            // 如果不为null则继续读
            ListBuffer<JCStatement> thenStatements = new ListBuffer<>();
            readPrimitive(thenStatements, getPrimitiveClass(type), treeMaker.Ident(variable.name));
            JCBlock thenBody = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());

            ListBuffer<JCStatement> elseStatements = new ListBuffer<>();
            readNull(elseStatements, treeMaker.Ident(variable.name));
            JCBlock elseBody = treeMaker.Block(BLOCK_MODIFIERS, elseStatements.toList());

            statements.append(treeMaker.If(notNull, thenBody, elseBody));
        } else {
            readPrimitive(statements, getPrimitiveClass(type), treeMaker.Ident(variable.name));
        }
    }

    /**
     * 读基础类型
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readPrimitive(ListBuffer<JCStatement> statements, TypeMirror type, JCExpression variable) {
        // 包装类型得先读个布尔值
        if (isWrapper(type)) {
            // 先读一个标志
            JCExpression notNull = treeMaker.Exec(doReadAnPrimitive(boolean.class)).getExpression();
            // 如果不为null则继续读
            ListBuffer<JCStatement> thenStatements = new ListBuffer<>();
            readPrimitive(thenStatements, getPrimitiveClass(type), variable);
            JCBlock thenBody = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());

            ListBuffer<JCStatement> elseStatements = new ListBuffer<>();
            readNull(elseStatements, variable);
            JCBlock elseBody = treeMaker.Block(BLOCK_MODIFIERS, elseStatements.toList());

            statements.append(treeMaker.If(notNull, thenBody, elseBody));
        } else {
            readPrimitive(statements, getPrimitiveClass(type), variable);
        }
    }

    /**
     * 读基础类型
     * @param statements 方法体stats
     * @param primitiveClass 基础类型
     * @param variable 变量
     */
    private void readPrimitive(ListBuffer<JCStatement> statements, Class<?> primitiveClass, JCExpression variable) {
        JCMethodInvocation jcMethodInvocation = doReadAnPrimitive(primitiveClass);
        // xxx = xxx;
        JCAssign jcAssign = treeMaker.Assign(variable, jcMethodInvocation);
        statements.append(treeMaker.Exec(jcAssign));
    }

    /**
     * 读一个基础类型
     * @param primitiveClass 基础类型
     * @return JCExpressionStatement
     */
    private JCMethodInvocation doReadAnPrimitive(Class<?> primitiveClass) {
        String readAccess = "read" + Utils.toUpperCaseFirst(primitiveClass.getSimpleName());
        return treeMaker.Apply(List.nil(), memberAccess(names.fromString(SerializerBound.READ.paramName), readAccess), List.nil());
    }

    /**
     * 读一个null
     * @param statements 方法体stats
     * @param variable 变量
     */
    private void readNull(ListBuffer<JCStatement> statements, JCExpression variable) {
        statements.append(treeMaker.Exec(treeMaker.Assign(variable, literalNull())));
    }

    /**
     * 读一个数组
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readArray(ListBuffer<JCStatement> statements, ArrayType type, JCVariableDecl variable) {
        // 数组子元素类型
        Type elementType = type.elemtype;
        // 先读一个标志
        JCExpression notNull = treeMaker.Exec(doReadAnPrimitive(boolean.class)).getExpression();
        // 如果不为null则展开数组
        ListBuffer<JCStatement> thenStatements = new ListBuffer<>();

        Name lenName = variable.name.append(names.fromString("Len"));
        // 读出数组长度
        JCVariableDecl arrayLen = localVariableDef(lenName, treeMaker.TypeIdent(TypeTag.INT), null);
        readPrimitive(thenStatements.append(arrayLen), int.class, treeMaker.Ident(lenName));
        // 再new一个数组
        JCNewArray array = treeMaker.NewArray(treeMaker.Type(elementType), List.of(treeMaker.Ident(lenName)), null);
        thenStatements.append(treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(variable.name), array)));
        // 然后for循环
        ListBuffer<JCStatement> forStatements = new ListBuffer<>();
        // 索引 name_i
        Name stepName = variable.getName().append(names.fromString("_i"));
        // int name_i = 0;
        List<JCStatement> init = List.of(localVariableDef(stepName, treeMaker.TypeIdent(TypeTag.INT), treeMaker.Literal(0)));
        // name_i < nameLen
        JCBinary cond = treeMaker.Binary(Tag.LT, treeMaker.Ident(stepName), treeMaker.Ident(lenName));
        // name_i++
        List<JCExpressionStatement> step = List.of(autoIncrement(stepName));
        // 子元素名称
        Name elementName = variable.name.append(names.fromString(ELEMENT));
        JCArrayAccess element = treeMaker.Indexed(treeMaker.Ident(variable.getName()), treeMaker.Ident(stepName));
        // 多维数组递归
        if (isArray(elementType)) {
            JCVariableDecl elementVariable = localVariableDef(elementName, treeMaker.Type(elementType), literalNull());
            readArray(forStatements.append(elementVariable), (ArrayType) elementType, elementVariable);
            forStatements.append(treeMaker.Exec(treeMaker.Assign(element, treeMaker.Ident(elementName))));
            thenStatements.append(treeMaker.ForLoop(init, cond, step, treeMaker.Block(BLOCK_MODIFIERS, forStatements.toList())));
            JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
            statements.append(treeMaker.If(notNull, body, null));
            return;
        }
        // 基础类型直接读
        if (isPrimitiveType(elementType)) {
            readPrimitive(forStatements, elementType, element);
        } else if (isSerializable(elementType) && !isAbstract(elementType)) {
            readSerializable(forStatements, elementType, element);
        } else {
            JCVariableDecl elementVariable = localVariableDef(elementName, treeMaker.Type(elementType), literalNull());
            readVariable(forStatements.append(elementVariable), elementType, elementVariable);
            forStatements.append(treeMaker.Exec(treeMaker.Assign(element, treeMaker.Ident(elementName))));
        }

        thenStatements.append(treeMaker.ForLoop(init, cond, step, treeMaker.Block(BLOCK_MODIFIERS, forStatements.toList())));
        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
        statements.append(treeMaker.If(notNull, body, null));
    }

    /**
     * 读一个Collection
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readCollection(ListBuffer<JCStatement> statements, TypeMirror type, JCVariableDecl variable) {
        JCExpression notNull = treeMaker.Exec(doReadAnPrimitive(boolean.class)).getExpression();
        // 如果不为null则展开List
        ListBuffer<JCStatement> thenStatements = new ListBuffer<>();
        ListBuffer<JCStatement> forStatements = new ListBuffer<>();
        // 读出数组长度
        Name lenName = variable.name.append(names.fromString("Len"));
        JCVariableDecl arrayLen = localVariableDef(lenName, treeMaker.TypeIdent(TypeTag.INT), null);
        readPrimitive(thenStatements.append(arrayLen), int.class, treeMaker.Ident(lenName));
        // 集合类型
        Type collectionType = (Type) type;
        // for展开
        Type elementType = collectionType.getTypeArguments().head;
        // 泛型参数
        JCExpression typeArgs = treeMaker.Type(elementType);
        JCExpression listImpl; List<JCExpression> args;
        // 声明的是接口，则统一用ArrayList实例化
        if (collectionType.isInterface()) {
            listImpl = isSet(collectionType) ? memberAccess(SET_IMPL) : (isQueue(collectionType) ? memberAccess(QUEUE_IMPL) : memberAccess(LIST_IMPL));
            args = List.of(treeMaker.Ident(lenName));
        } else {
            listImpl = treeMaker.Type((Type) types.erasure(collectionType));
            args = List.nil();
        }
        // new一个List
        JCNewClass newList = treeMaker.NewClass(null, List.of(typeArgs), treeMaker.TypeApply(listImpl, List.of(typeArgs)), args, null);
        thenStatements.append(treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(variable.name), treeMaker.Exec(newList).getExpression())));
        // 索引 name_i
        Name stepName = variable.getName().append(names.fromString("_i"));
        // int name_i = 0;
        List<JCStatement> init = List.of(localVariableDef(stepName, treeMaker.TypeIdent(TypeTag.INT), treeMaker.Literal(0)));
        // name_i < nameLen
        JCBinary cond = treeMaker.Binary(Tag.LT, treeMaker.Ident(stepName), treeMaker.Ident(lenName));
        // name_i++
        List<JCExpressionStatement> step = List.of(autoIncrement(stepName));
        // 如果是基础类型直接add
        if (elementType.getKind().isPrimitive()) {
            List<JCExpression> addArgs = List.of(doReadAnPrimitive(getPrimitiveClass(elementType)));
            forStatements.append(treeMaker.Exec(treeMaker.Apply(List.nil(), memberAccess(variable.name, "add"), addArgs)));
        } else {
            Name elementName = variable.name.append(names.fromString(ELEMENT));
            JCVariableDecl element = localVariableDef(elementName, treeMaker.Type(elementType), literalNull());
            readVariable(forStatements.append(element), elementType, element);
            List<JCExpression> addArgs = List.of(treeMaker.Ident(elementName));
            forStatements.append(treeMaker.Exec(treeMaker.Apply(List.nil(), memberAccess(variable.name, "add"), addArgs)));
        }

        thenStatements.append(treeMaker.ForLoop(init, cond, step, treeMaker.Block(BLOCK_MODIFIERS, forStatements.toList())));
        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
        statements.append(treeMaker.If(notNull, body, null));
    }

    /**
     * 读一个map
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readMap(ListBuffer<JCStatement> statements, TypeMirror type, JCVariableDecl variable) {
        JCExpression notNull = treeMaker.Exec(doReadAnPrimitive(boolean.class)).getExpression();
        // 如果不为null则展开读
        ListBuffer<JCStatement> thenStatements = new ListBuffer<>();
        ListBuffer<JCStatement> forStatements = new ListBuffer<>();
        // 读出数组长度
        Name lenName = variable.name.append(names.fromString("Len"));
        JCVariableDecl arrayLen = localVariableDef(lenName, treeMaker.TypeIdent(TypeTag.INT), null);
        readPrimitive(thenStatements.append(arrayLen), int.class, treeMaker.Ident(lenName));
        // 集合类型
        Type mapType = (Type) type;
        Type keyType = mapType.getTypeArguments().head;
        Type valueType = mapType.getTypeArguments().last();
        // 泛型参数
        ListBuffer<JCExpression> typeArgs = new ListBuffer<>();
        mapType.getTypeArguments().forEach(t -> typeArgs.append(treeMaker.Type(t)));
        JCExpression mapImpl; List<JCExpression> args;
        // 声明的是接口，则统一用HashMap实例化
        if (mapType.isInterface()) {
            mapImpl = memberAccess(MAP_IMPL);
            args = List.of(treeMaker.Ident(lenName));
        } else {
            mapImpl = treeMaker.Type((Type) types.erasure(mapType));
            args = List.nil();
        }
        // new一个Map
        JCNewClass newMap = treeMaker.NewClass(null, typeArgs.toList(), treeMaker.TypeApply(mapImpl, typeArgs.toList()), args, null);
        thenStatements.append(treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(variable.name), treeMaker.Exec(newMap).getExpression())));
        // for展开
        // 索引 name_i
        Name stepName = variable.getName().append(names.fromString("_i"));
        // int name_i = 0;
        List<JCStatement> init = List.of(localVariableDef(stepName, treeMaker.TypeIdent(TypeTag.INT), treeMaker.Literal(0)));
        // name_i < nameLen
        JCBinary cond = treeMaker.Binary(Tag.LT, treeMaker.Ident(stepName), treeMaker.Ident(lenName));
        // name_i++
        List<JCExpressionStatement> step = List.of(autoIncrement(stepName));
        ListBuffer<JCExpression> putArgs = new ListBuffer<>();
        Name keyName = variable.name.append(names.fromString("Key"));
        Name valueName = variable.name.append(names.fromString("Value"));

        readMapArgs(keyName, forStatements, putArgs, keyType);
        readMapArgs(valueName, forStatements, putArgs, valueType);

        forStatements.append(treeMaker.Exec(treeMaker.Apply(List.nil(), memberAccess(variable.name, "put"), putArgs.toList())));

        thenStatements.append(treeMaker.ForLoop(init, cond, step, treeMaker.Block(BLOCK_MODIFIERS, forStatements.toList())));
        JCBlock body = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());
        statements.append(treeMaker.If(notNull, body, null));
    }

    /**
     * 读map的参数
     * @param name 参数名
     * @param statements 方法体stats
     * @param putArgs 参数列表
     * @param type 类型
     */
    private void readMapArgs(Name name, ListBuffer<JCStatement> statements, ListBuffer<JCExpression> putArgs, Type type) {
        // 如果是基础类型直接put
        if (type.getKind().isPrimitive()) {
            putArgs.append(doReadAnPrimitive(getPrimitiveClass(type)));
        } else {
            JCVariableDecl valueElement = localVariableDef(name, treeMaker.Type(type), literalNull());
            readVariable(statements.append(valueElement), type, valueElement);
            putArgs.append(treeMaker.Ident(name));
        }
    }

    /**
     * 读一个自定义序列化类型
     * @param statements 方法体stats
     * @param type 变量类型
     * @param variable 变量
     */
    private void readSerializable(ListBuffer<JCStatement> statements, TypeMirror type, JCExpression variable) {
        // 先读一个null
        JCExpression notNull = treeMaker.Exec(doReadAnPrimitive(boolean.class)).getExpression();
        ListBuffer<JCStatement> thenStatements = new ListBuffer<>();

        Type clazzType = (Type) type;
        // 不为null,则new一个对象
        JCNewClass newObject;
        JCExpression clazz = treeMaker.Type(clazzType);
        if (clazzType.getTypeArguments().size() > 0) {
            ListBuffer<JCExpression> typeArgs = new ListBuffer<>();
            clazzType.getTypeArguments().forEach(e -> typeArgs.append(treeMaker.Type(e)));
            newObject = treeMaker.NewClass(null, typeArgs.toList(), treeMaker.TypeApply(clazz, typeArgs.toList()), List.nil(), null);
        } else {
            newObject = treeMaker.NewClass(null, List.nil(), clazz, List.nil(), null);
        }

        thenStatements.append(treeMaker.Exec(treeMaker.Assign(variable, treeMaker.Exec(newObject).getExpression())));
        // xxx.readFrom(in);
        JCExpression readExec = treeMaker.Select(variable, names.fromString(SerializerBound.READ.accessName));
        JCExpression args = treeMaker.Ident(names.fromString(SerializerBound.READ.paramName));
        thenStatements.append(treeMaker.Exec(treeMaker.Apply(List.nil(), readExec, List.of(args))));
        JCBlock thenBody = treeMaker.Block(BLOCK_MODIFIERS, thenStatements.toList());

        ListBuffer<JCStatement> elseStatements = new ListBuffer<>();
        elseStatements.append(treeMaker.Exec(treeMaker.Assign(variable, literalNull())));
        JCBlock elseBody = treeMaker.Block(BLOCK_MODIFIERS, elseStatements.toList());

        statements.append(treeMaker.If(notNull, thenBody, elseBody));
    }

    /**
     * 读一个对象
     * @param statements 方法体stats
     * @param variable 变量
     */
    private void readObject(ListBuffer<JCStatement> statements, JCExpression variable) {
        JCExpression readExec = memberAccess(names.fromString(SerializerBound.READ.paramName), "read");
        JCAssign assign = treeMaker.Assign(variable, treeMaker.Apply(List.nil(), readExec, List.nil()));
        statements.append(treeMaker.Exec(assign));
    }

    /**
     * ++自增
     * @param name 变量名
     * @return JCExpressionStatement
     */
    private JCExpressionStatement autoIncrement(Name name) {
        return treeMaker.Exec(treeMaker.Unary(Tag.PREINC, treeMaker.Ident(name)));
    }

    /**
     * 常量null
     */
    private JCLiteral literalNull() {
        return treeMaker.Literal(TypeTag.BOT, null);
    }

    /**
     * 定义一个局部变量
     * @param name 变量名
     * @param vartype 变量类型
     * @param init 初始值
     * @return 变量定义
     */
    private JCVariableDecl localVariableDef(Name name, JCExpression vartype, JCExpression init) {
        return treeMaker.VarDef(treeMaker.Modifiers(LOCAL_VARIABLE_MODIFIERS), name, vartype, init);
    }

    /**
     * 生成访问表达式
     * @return JCExpression
     */
    private JCExpression memberAccess(String components) {
        String[] componentArray = components.split("\\.");
        JCExpression expr = treeMaker.Ident(names.fromString(componentArray[0]));
        for (int i = 1; i < componentArray.length; ++i) {
            expr = treeMaker.Select(expr, names.fromString(componentArray[i]));
        }
        return expr;
    }

    /**
     * 生成访问表达式
     * @return JCExpression
     */
    private JCExpression memberAccess(Name name, String... components) {
        JCExpression expr = treeMaker.Ident(name);
        for (String str : components) {
            expr = treeMaker.Select(expr, names.fromString(str));
        }
        return expr;
    }

    /**
     * 生成访问表达式
     * @return JCExpression
     */
    private JCExpression memberAccess(Name name, Name... components) {
        JCExpression expr = treeMaker.Ident(name);
        for (Name selector : components) {
            expr = treeMaker.Select(expr, selector);
        }
        return expr;
    }

    /**
     * 是否是Serializable接口的实现
     * @param type element types
     * @return 是 true
     */
    private boolean isSerializableAssignableFrom(TypeMirror type) {
        // 递归查找父类
        for (TypeMirror superType : types.directSupertypes(type)) {
            if (isSerializable(superType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否可序列化(以Serializable注解为准)
     * @param type element types
     * @return 可以 true
     */
    private boolean isSerializable(TypeMirror type) {
        if (types.asElement(type).getAnnotation(Serializable.class) != null) {
            return true;
        }
        // 递归查找父类
        for (TypeMirror superType : types.directSupertypes(type)) {
            if (isSerializable(superType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否是某个类的子类（包括本身）
     * @param type 类型
     * @param clazz 基类
     * @return 是 true
     */
    private boolean isAssignableFrom(TypeMirror type, Class<?> clazz) {
        // 先擦除泛型
        TypeMirror erasureType = types.erasure(type);
        if (erasureType.toString().equals(clazz.getTypeName())) {
            return true;
        }
        // 递归查找父类
        for (TypeMirror superType : types.directSupertypes(type)) {
            if (isAssignableFrom(superType, clazz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否是抽象类
     * @param type 类型
     * @return 是 true
     */
    private boolean isAbstract(TypeMirror type) {
        return types.asElement(type).getModifiers().contains(Modifier.ABSTRACT);
    }
    
    /**
     * 是否基础类型（包含包装类型）
     * @param type 类型
     * @return 是 true
     */
    private boolean isPrimitiveType(TypeMirror type) {
        // 基础类型
        if (type.getKind().isPrimitive()) {
            return true;
        }
        //擦除一下泛型
        TypeMirror erasureType = types.erasure(type);
        String typeName = Utils.getTypeName(erasureType.toString());
        return Utils.WRAPPER_PRIMITIVE_MAP.containsKey(typeName);
    }

    /**
     * 是否是包装类型
     * @param type 类型
     * @return 是 true
     */
    private boolean isWrapper(TypeMirror type) {
        //擦除一下泛型
        TypeMirror erasureType = types.erasure(type);
        String typeName = Utils.getTypeName(erasureType.toString());
        return Utils.WRAPPER_PRIMITIVE_MAP.containsKey(typeName);
    }

    /**
     * 获取基础类型类
     * @param type 类型
     * @return Class
     */
    private Class<?> getPrimitiveClass(TypeMirror type) {
        String typeName = Utils.getTypeName(type.toString());
        Class<?> clazz = Utils.PRIMITIVE_MAP.get(typeName);
        if (clazz != null) {
            return clazz;
        }
        return Utils.WRAPPER_PRIMITIVE_MAP.get(typeName);
    }

    /**
     * 是否是数组
     * @param type 类型
     * @return 是 true
     */
    private boolean isArray(TypeMirror type) {
        return type.getKind() == TypeKind.ARRAY;
    }

    /**
     * 是否是集合
     * @param type 类型
     * @return 是 true
     */
    private boolean isCollection(TypeMirror type) {
        return isAssignableFrom(type, Collection.class);
    }

    /**
     * 是否是Map
     * @param type 类型
     * @return 是 true
     */
    private boolean isMap(TypeMirror type) {
        return isAssignableFrom(type, Map.class);
    }

    /**
     * 是否是Set
     * @param type 类型
     * @return 是 true
     */
    private boolean isSet(TypeMirror type) {
        return isAssignableFrom(type, Set.class);
    }

    /**
     * 是否是Queue
     * @param type 类型
     * @return 是 true
     */
    private boolean isQueue(TypeMirror type) {
        return isAssignableFrom(type, Queue.class);
    }

    /**
     * 输出警告信息
     * @param className 类名
     * @param msg 警告信息
     */
    private void printWarning(String className, String msg) {
        messager.printMessage(Kind.WARNING, "Class: " + className + " [ " + msg + " ]");
    }

    /**
     * 输出错误信息
     * @param className 类名
     * @param msg 错误信息
     */
    private void printError(String className, String msg) {
        messager.printMessage(Kind.ERROR, "Class: " + className + " [ " + msg + " ]");
    }

    /** java文件包装 */
    private class JavaSourceWrapper {
        /** 类组 */
        private Map<String, JavaClassWrapper> classMap = new HashMap<>();

        /**
         * 放入一个类定义
         * @param element 类元素
         * @return 类包装
         */
        JavaClassWrapper computeIfAbsent(TypeElement element) {
            JCClassDecl jcClassDecl = trees.getTree(element);
            return classMap.computeIfAbsent(jcClassDecl.getSimpleName().toString(), k -> {
                JavaClassWrapper clazz = new JavaClassWrapper(element);
                // 获取抽象语法树
                clazz.classDecl = jcClassDecl;
                return clazz;
            });
        }

        /**
         * 遍历类
         * @param consumer Consumer<JavaClassWrapper>
         */
        void forEach(Consumer<JavaClassWrapper> consumer) {
            classMap.values().forEach(consumer);
        }
    }

    /** 类包装 */
    private class JavaClassWrapper {
        /** 类元素 */
        TypeElement element;
        /** 类定义 */
        JCClassDecl classDecl;
        /** 变量字段组 */
        List<VariableWrapper> variableList = List.nil();

        JavaClassWrapper(TypeElement element) {
            this.element = element;
        }

        /**
         * 是否不可序列化
         * @return 不可序列化 true
         */
        boolean unableSerializable(Element element) {
            TypeMirror type = element.asType();
            // 看是否有SerializerField注解
            SerializerField annotation = element.getAnnotation(SerializerField.class);
            // 自定义强制序列化的
            if (annotation != null && annotation.uncheck()) {
                return false;
            }
            // 如果是数组
            if (type instanceof ArrayType) {
                type = Utils.erasureArray((ArrayType) type);
            }
            // 基础类型都支持序列化
            if (isPrimitiveType(type)) {
                return false;
            }
            // 集合
            if (type.getKind() == TypeKind.DECLARED) {
                // Map、List 递归展开
                if (isCollection(type) || isMap(type)) {
                    Result<Boolean> unableSerialize = new Result<>(false);
                    DeclaredType declaredType = (DeclaredType) type;
                    declaredType.getTypeArguments().forEach(typeArgs -> {
                        if (typeArgs.getKind() != TypeKind.TYPEVAR && unableSerializable(types.asElement(typeArgs))) {
                            unableSerialize.value = true;
                        }
                    });
                    return unableSerialize.value;
                }
            }
            // 自定义序列化对象
            return !isSerializable(type);
        }

        /**
         * 增加变量定义
         * @param element 变量元素
         * @param jcVariableDecl 变量定义
         */
        void addVariableDecl(Element element, JCVariableDecl jcVariableDecl) {
            if (element.getModifiers().contains(Modifier.FINAL)) {
                printError(classDecl.getSimpleName().toString(), "用final修饰的字段无法序列化，请检查! Variable: " + jcVariableDecl.getName());
                return;
            }
            if (unableSerializable(element)) {
                printError(classDecl.getSimpleName().toString(), "无法序列化的对象，请检查! Variable: " + jcVariableDecl.getName());
                return;
            }
            this.variableList = variableList.append(new VariableWrapper(element, jcVariableDecl));
        }

        /**
         * 增加方法
         * @param newMethodDecl 方法定义
         */
        void addMethodDecl(JCMethodDecl newMethodDecl) {
            if (newMethodDecl == null) {
                return;
            }
            ListBuffer<JCTree> defList = new ListBuffer<>();
            Result<JCMethodDecl> result = new Result<>(newMethodDecl);
            // 如果有相同的方法则直接替换方法体
            classDecl.defs.forEach(def -> {
                if (def.getTag() == Tag.METHODDEF) {
                    JCMethodDecl jcMethodDecl = (JCMethodDecl) def;
                    if (!Utils.methodEquals(jcMethodDecl, newMethodDecl)) {
                        defList.append(jcMethodDecl);
                    } else {
                        if (jcMethodDecl.body.getStatements().size() > 0) {
                            printWarning(getSimpleName(), "Replace Method: " + newMethodDecl.name);
                        }
                        // 替换掉方法体
                        result.value = jcMethodDecl;
                        result.value.body = newMethodDecl.body;
                    }
                } else {
                    defList.append(def);
                }
            });
            classDecl.defs = defList.append(result.value).toList();
        }

        /**
         * 增加import
         * @param jcImport import
         */
        void addImport(JCImport jcImport) {
            TreePath treePath = trees.getPath(element);
            if (jcImport == null || treePath == null) {
                return;
            }
            CompilationUnitTree unitTree = treePath.getCompilationUnit();
            if (!(unitTree instanceof JCCompilationUnit)) {
                return;
            }
            JCCompilationUnit jcCompilationUnit = (JCCompilationUnit) unitTree;
            jcCompilationUnit.defs = jcCompilationUnit.defs.append(jcImport);
        }

        /**
         * 遍历字段/变量
         * @param consumer Consumer<VariableWrapper>
         */
        void forEach(Consumer<VariableWrapper> consumer) {
            variableList.forEach(consumer);
        }

        /**
         * 获取类名
         * @return 类名
         */
        String getSimpleName() {
            return classDecl.getSimpleName().toString();
        }
    }

    /** 变量字段包装 */
    private class VariableWrapper {
        /** 字段元素 */
        Element element;
        /** 字段定义 */
        JCVariableDecl variable;

        VariableWrapper(Element element, JCVariableDecl variable) {
            this.element = element;
            this.variable = variable;
        }
    }

    /**
     * 用于闭包的result
     */
    private static class Result<T> {
        /** 结果的值 */
        private T value;

        Result(T value) {
            this.value = value;
        }
    }
}
