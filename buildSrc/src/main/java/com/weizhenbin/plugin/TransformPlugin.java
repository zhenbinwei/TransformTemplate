package com.weizhenbin.plugin;

import com.android.build.gradle.BaseExtension;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * @Author evan.wei.xl
 * @Date 2022/5/21-7:31 下午
 * @DESC
 */
public class TransformPlugin implements Plugin<Project> {
    @Override
    public void apply(Project target) {
        BaseExtension android = target.getExtensions().getByType(BaseExtension.class);
        //注册 registerTransform
        android.registerTransform(new TransformTemplate("MyTransform",false,new MyTransform()));
    }
}
