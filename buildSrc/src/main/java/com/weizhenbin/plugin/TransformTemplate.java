package com.weizhenbin.plugin;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.SecondaryFile;
import com.android.build.api.transform.Status;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.tasks.ExtractProfilerNativeDependenciesTaskKt;
import com.android.builder.utils.ZipEntryUtils;
import com.android.utils.FileUtils;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import org.gradle.internal.impldep.com.amazonaws.util.IOUtils;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.android.build.api.transform.QualifiedContent.DefaultContentType.CLASSES;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @Date 2022/5/21-3:16 下午
 * @DESC
 */
public class TransformTemplate extends Transform {

    @NonNull
    private final String name;
    private final BiConsumer<InputStream, OutputStream> function;
    private boolean isIncremental;

    /**
     * Creates the transform.
     */
    public TransformTemplate(@NotNull String name, boolean isIncremental, BiConsumer<InputStream, OutputStream> function) {
        this.function = function;
        this.name = name;
        this.isIncremental = isIncremental;
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @NonNull
    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }
    @NonNull
    @Override
    public Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT;
    }

    @Override
    public boolean isIncremental() {
        return isIncremental;
    }

    @Override
    public void transform(@NonNull TransformInvocation invocation)
            throws InterruptedException, IOException {
        final TransformOutputProvider outputProvider = invocation.getOutputProvider();
        assert outputProvider != null;

        // Output the resources, we only do this if this is not incremental,
        // as the secondary file is will trigger a full build if modified.
        if (!invocation.isIncremental()) {
            outputProvider.deleteAll();
        }
        for (TransformInput ti : invocation.getInputs()) {
            for (JarInput jarInput : ti.getJarInputs()) {
                File inputJar = jarInput.getFile();
                File outputJar =
                        outputProvider.getContentLocation(
                                jarInput.getName(),
                                jarInput.getContentTypes(),
                                jarInput.getScopes(),
                                Format.JAR);
                if (invocation.isIncremental()) {
                    switch (jarInput.getStatus()) {
                        case NOTCHANGED:
                            break;
                        case ADDED:
                        case CHANGED:
                            transformJar(function, inputJar, outputJar);
                            break;
                        case REMOVED:
                            FileUtils.delete(outputJar);
                            break;
                    }
                } else {
                    transformJar(function, inputJar, outputJar);
                }
            }
            for (DirectoryInput di : ti.getDirectoryInputs()) {
                File inputDir = di.getFile();
                File outputDir =
                        outputProvider.getContentLocation(
                                di.getName(),
                                di.getContentTypes(),
                                di.getScopes(),
                                Format.DIRECTORY);
                if (invocation.isIncremental()) {
                    for (Map.Entry<File, Status> entry : di.getChangedFiles().entrySet()) {
                        File inputFile = entry.getKey();
                        switch (entry.getValue()) {
                            case NOTCHANGED:
                                break;
                            case ADDED:
                            case CHANGED:
                                if (!inputFile.isDirectory()
                                        && inputFile.getName()
                                        .endsWith(SdkConstants.DOT_CLASS)) {
                                    File out = toOutputFile(outputDir, inputDir, inputFile);
                                    transformFile(function, inputFile, out);
                                }
                                break;
                            case REMOVED:
                                File outputFile = toOutputFile(outputDir, inputDir, inputFile);
                                FileUtils.deleteIfExists(outputFile);
                                break;
                        }
                    }
                } else {
                    for (File in : FileUtils.getAllFiles(inputDir)) {
                        if (in.getName().endsWith(SdkConstants.DOT_CLASS)) {
                            File out = toOutputFile(outputDir, inputDir, in);
                            transformFile(function, in, out);
                        }
                    }
                }
            }
        }
    }

    private void copy(InputStream inputStream,OutputStream outputStream){
        byte[] buffer = new byte[1024];
        int len = 0;
        try {
            len = inputStream.read(buffer);
            while (len != -1) {
                outputStream.write(buffer, 0, len);
                len = inputStream.read(buffer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void transformJar(
            BiConsumer<InputStream, OutputStream> function, File inputJar, File outputJar)
            throws IOException {
        createParentDirs(outputJar);
       try (FileInputStream fis = new FileInputStream(inputJar);
             ZipInputStream zis = new ZipInputStream(fis);
             FileOutputStream fos = new FileOutputStream(outputJar);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            ZipEntry entry = zis.getNextEntry();
            while (entry != null && ZipEntryUtils.isValidZipEntryName(entry)) {
                if (!entry.isDirectory() && entry.getName().endsWith(SdkConstants.DOT_CLASS)) {
                    ZipEntry nextEntry = new ZipEntry(entry.getName());
                    nextEntry.setTime(-1L);
                    zos.putNextEntry(nextEntry);
                    apply(function, zis, zos);
                } else {
                    // Do not copy resources
                    ZipEntry nextEntry = new ZipEntry(entry.getName());
                    nextEntry.setTime(-1L);
                    zos.putNextEntry(nextEntry);
                    byte[] buffer = new byte[1024];
                    int len = 0;
                    try {
                        len = zis.read(buffer);
                        while (len != -1) {
                            zos.write(buffer, 0, len);
                            len = zis.read(buffer);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                entry = zis.getNextEntry();
            }
        }
    }


    public static void createParentDirs(File file) throws IOException {
        checkNotNull(file);
        File parent = file.getCanonicalFile().getParentFile();
        System.out.println("parent:"+parent);
        if (parent == null) {
            /*
             * The given directory is a filesystem root. All zero of its ancestors exist. This doesn't
             * mean that the root itself exists -- consider x:\ on a Windows machine without such a drive
             * -- or even that the caller can create it, but this method makes no such guarantees even for
             * non-root files.
             */
            return;
        }
        parent.mkdirs();
        if (!parent.isDirectory()) {
            throw new IOException("Unable to create parent directories of " + file);
        }
    }
    private void transformFile(
            BiConsumer<InputStream, OutputStream> function, File inputFile, File outputFile)
            throws IOException {
        Files.createParentDirs(outputFile);
        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(outputFile)) {
            apply(function, fis, fos);
        }
    }

    @NonNull
    private static File toOutputFile(File outputDir, File inputDir, File inputFile) {
        return new File(outputDir, FileUtils.relativePossiblyNonExistingPath(inputFile, inputDir));
    }

    private void apply(
            BiConsumer<InputStream, OutputStream> function, InputStream in, OutputStream out)
            throws IOException {
        try {
            function.accept(in, out);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }



    /*private static final FileTime ZERO = FileTime.fromMillis(0);
    private static final String  FILE_SEP = File.separator;

    //解压jar 然后在打包到新到jar里
    public void transformJar(File inputJar, File outputJar) throws IOException {
        ZipFile inputZip = new ZipFile(inputJar);
        ZipOutputStream outputZip = new ZipOutputStream(new BufferedOutputStream(
                Files.newOutputStream(outputJar.toPath())));
        Enumeration<? extends ZipEntry> inEntries = inputZip.entries();
        while (inEntries.hasMoreElements()) {
            ZipEntry entry = inEntries.nextElement();
            InputStream originalFile =
                    new BufferedInputStream(inputZip.getInputStream(entry));
            ZipEntry outEntry = new ZipEntry(entry.getName());
            byte[] newEntryContent;
            //找出需要插桩的类okhttp3/OkHttpClient.class
            //   okhttp3/OkHttpClient$Builder.class
            // System.out.println("------outEntry.getName():"+outEntry.getName());
            if (checkClassFile(outEntry.getName().replace("/", "."))) {
                // newEntryContent = IOUtils.toByteArray(originalFile);
                newEntryContent = ASMInject.injectCode(originalFile,outEntry.getName());
                System.out.println("------------entry name :"+entry.getName());
            } else {
                // newEntryContent = transform(IOUtils.toByteArray(originalFile));
                newEntryContent = IOUtils.toByteArray(originalFile);
            }
            CRC32 crc32 = new CRC32();
            crc32.update(newEntryContent);
            outEntry.setCrc(crc32.getValue());
            outEntry.setMethod(ZipEntry.STORED);
            outEntry.setSize(newEntryContent.length);
            outEntry.setCompressedSize(newEntryContent.length);
            outEntry.setLastAccessTime(ZERO);
            outEntry.setLastModifiedTime(ZERO);
            outEntry.setCreationTime(ZERO);
            outputZip.putNextEntry(outEntry);
            outputZip.write(newEntryContent);
            outputZip.closeEntry();
        }
        outputZip.flush();
        outputZip.close();
    }*/

}
