package com.weizhenbin.plugin;


import org.gradle.api.UncheckedIOException;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.ASM4;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;

/**
 * @Author evan.wei.xl
 * @Date 2022/5/21-8:12 下午
 * @DESC
 */
public class MyTransform implements BiConsumer<InputStream, OutputStream>{

    @Override
    public void accept(InputStream inputStream, OutputStream outputStream) {
        //第一种方式
       /* ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = writer;
            visitor = new ClassPrint(visitor);
        try {
            ClassReader cr = new ClassReader(inputStream);
            cr.accept(visitor, 0);
            outputStream.write(writer.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }*/


        //第二种方式
        try {
            ClassReader cr = new ClassReader(inputStream);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);
            classNode.methods.forEach(new Consumer<MethodNode>() {
                @Override
                public void accept(MethodNode methodNode) {
                    methodNode.instructions.forEach(new Consumer<AbstractInsnNode>() {
                        @Override
                        public void accept(AbstractInsnNode abstractInsnNode) {
                            // if (owner.equals("android/telephony/TelephonyManager")&&name.equals("getDeviceId")){
                            //                System.out.println("修改方法");
                            //                mv.visitMethodInsn(INVOKESTATIC,"com/example/gradledemo/MyTelephonyManager","getDeviceId","(Landroid/telephony/TelephonyManager;)Ljava/lang/String;",isInterface);
                            //                return;
                            //            }
                            if (abstractInsnNode instanceof MethodInsnNode){
                                MethodInsnNode methodInsnNode = (MethodInsnNode) abstractInsnNode;
                                if (methodInsnNode.owner.equals("android/telephony/TelephonyManager")&&methodInsnNode.name.equals("getDeviceId")){
                                    methodInsnNode.owner = "com/example/gradledemo/MyTelephonyManager";
                                    methodInsnNode.desc = "(Landroid/telephony/TelephonyManager;)Ljava/lang/String;";
                                    methodInsnNode.name = "getDeviceId";
                                    methodInsnNode.setOpcode(INVOKESTATIC);
                                }
                            }
                        }
                    });
                }
            });
            classNode.accept(writer);
            outputStream.write(writer.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    @NotNull
    @Override
    public BiConsumer<InputStream, OutputStream> andThen(@NotNull BiConsumer<? super InputStream, ? super OutputStream> after) {
        return null;
    }


    static class ClassPrint extends ClassVisitor{
        public ClassPrint( ClassVisitor classVisitor) {
            super(Opcodes.ASM7, classVisitor);
        }
        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            System.out.println("className:"+name);
        }
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            System.out.println("access:"+access+"-name:"+name+"-descriptor:"+descriptor+"-signature:"+signature+"-exceptions:"+exceptions);
            MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            // System.out.println("methodVisitor:"+methodVisitor);
            return new MethodPrint(methodVisitor,access,name,descriptor);
        }
    }


    static class MethodPrint extends AdviceAdapter {
        protected MethodPrint(MethodVisitor methodVisitor, int access, String name, String descriptor) {
            super(ASM5, methodVisitor, access, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            System.out.println("opcode:"+opcode+"-owner:"+owner+"-name:"+name+"-descriptor:"+descriptor+"-isInterface:"+isInterface);
            if (owner.equals("android/telephony/TelephonyManager")&&name.equals("getDeviceId")){
                System.out.println("修改方法");
                mv.visitMethodInsn(INVOKESTATIC,"com/example/gradledemo/MyTelephonyManager",
                        "getDeviceId","(Landroid/telephony/TelephonyManager;)Ljava/lang/String;",isInterface);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }


}
