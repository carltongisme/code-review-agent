package org.example.domain.code.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.example.domain.code.model.ExtractedMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解析 Java 源码的抽象语法树（AST），提取每个方法的代码块及其调用关系。
 */
public class JavaCodeParser {

    private static final Logger log = LoggerFactory.getLogger(JavaCodeParser.class);

    /** 全局复用的 JavaParser 实例（线程安全） */
    private static final JavaParser parser = new JavaParser();

    public List<ExtractedMethod> parseFile(String sourceCode) {
        // 空文件或纯注释文件直接返回空列表
        if (sourceCode == null || sourceCode.isBlank()) {
            return List.of();
        }

        CompilationUnit cu;
        try {
            cu = parser.parse(sourceCode).getResult()
                .orElseThrow(() -> new IllegalArgumentException("无法解析 Java 源码"));
        } catch (Exception e) {
            log.warn("JavaParser 解析失败: {}", e.getMessage());
            return List.of();
        }

        // 获取包名作为类名前缀
        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");

        List<ExtractedMethod> methods = new ArrayList<>();

        // 遍历所有类/接口声明
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            // 类名（不含包名）
            String className = packageName.isEmpty()
                ? clazz.getNameAsString()
                : packageName + "." + clazz.getNameAsString();

            // 遍历类中所有方法声明
            for (MethodDeclaration method : clazz.getMethods()) {
                String methodSignature = buildSignature(method);
                String methodSource = method.toString();

                // 提取该方法体内调用的方法列表
                List<String> calledMethods = extractCallExpr(method);

                methods.add(new ExtractedMethod(
                    className,
                    methodSignature,
                    methodSource,
                    calledMethods
                ));

                log.debug("提取方法: {}::{}", className, methodSignature);
            }
        });

        log.debug("解析完成: {} 个方法 ({} 字节)", methods.size(), sourceCode.length());
        return methods;
    }

    public List<String> extractCalledMethods(String methodSourceCode) {
        if (methodSourceCode == null || methodSourceCode.isBlank()) {
            return List.of();
        }

        try {
            // 构造一个最小化的类包裹方法源码，使 JavaParser 能解析孤立的 method body
            String wrapped = "class _T { " + methodSourceCode + " }";
            CompilationUnit cu = parser.parse(wrapped).getResult().orElse(null);
            if (cu == null) return List.of();

            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            if (methods.isEmpty()) return List.of();

            return extractCallExpr(methods.getFirst());
        } catch (Exception e) {
            log.warn("提取调用方法失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 构建方法签名，包含参数类型。
     * 格式: "methodName(ParamType1,ParamType2)"
     */
    private String buildSignature(MethodDeclaration method) {
        StringBuilder sb = new StringBuilder(method.getNameAsString());
        sb.append("(");
        method.getParameters().forEach(p -> {
            if (sb.charAt(sb.length() - 1) != '(') {
                sb.append(",");
            }
            sb.append(p.getType().asString());
        });
        sb.append(")");
        return sb.toString();
    }

    /**
     * 从方法声明中提取所有被调用的方法表达式。
     */
    private List<String> extractCallExpr(MethodDeclaration method) {
        List<String> calls = new ArrayList<>();
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                super.visit(n, arg);
                // 提取调用表达式的作用域（如 a.b.c.method() → scope "a.b.c"）
                String scope = n.getScope()
                    .map(Object::toString)
                    .orElse("");
                String callSig = scope.isEmpty()
                    ? n.getNameAsString()
                    : scope + "::" + n.getNameAsString();
                calls.add(callSig);
            }
        }, null);
        return Collections.unmodifiableList(calls);
    }
}
