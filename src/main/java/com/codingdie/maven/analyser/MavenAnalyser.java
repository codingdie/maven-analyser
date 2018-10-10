package com.codingdie.maven.analyser;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @author xupeng
 * @date 2018/10/9
 */
public class MavenAnalyser {
    public static final Charset CHARSET = Charset.forName("UTF-8");


    public static Document generateTestPom(String orginalPom) throws Exception {
        Document document = DocumentHelper.parseText(orginalPom);
        document.selectNodes("//*[name()='dependencies']").forEach(item -> {
            item.getParent().element(item.getName()).clearContent();
        });

        return document;
    }

    public static void main(String[] args) {
        try {
            testOnePom(args[0]);
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
        }
    }

    private static void testOnePom(String path) throws Exception {
        String pom = FileUtils.readFileToString(new File(path), "utf-8");
        List<Dependency> dependencies = new ArrayList<>();
        Document document = DocumentHelper.parseText(pom);
        Element root = document.getRootElement();
        String artifactId = root.element("artifactId").getStringValue();
        List<Element> elements = new ArrayList<>();
        document.selectNodes("//*[name()='dependencies']").forEach(item -> {
            elements.addAll(item.getParent().element(item.getName()).elements("dependency"));
        });
        for (Element element : elements) {
            dependencies.add(testOneDependency(pom, element));
        }
        dependencies.sort(new Comparator<Dependency>() {
            @Override
            public int compare(Dependency o1, Dependency o2) {
                return (o1.time - o2.time) > 0 ? 1 : -1;
            }
        });
        List<String> stringList = dependencies.stream().map(Dependency::toString).collect(Collectors.toList());
        FileUtils.writeLines(new File("result/" + artifactId + ".txt"), stringList);
    }

    private static Dependency testOneDependency(String pom, Element dependencyEle) throws Exception {
        Dependency dependency = new Dependency(dependencyEle);
        Document testDocument = generateTestPom(pom);
        Element dependenciesEle = testDocument.getRootElement().element("dependencies");
        dependenciesEle.add(dependencyEle.createCopy());
        String workDir = "tmp/" + UUID.randomUUID().toString();
        File file = new File(workDir + "/pom.xml");
        File workFile = file.getParentFile();
        workFile.delete();
        file.delete();
        workFile.mkdirs();
        file.createNewFile();
        FileWriter out = new FileWriter(file);
        testDocument.getRootElement().write(out);
        out.close();
        List<String> cmds = new ArrayList<>();
        cmds.add("bash");
        cmds.add("-c");
        cmds.add("mvn  compile  -Dmaven.test.skip=true");
        File resultFile = new File(workDir + "/test.result");
        ProcessBuilder pb = new ProcessBuilder(cmds).directory(workFile).redirectOutput(resultFile);
        Map<String, AtomicInteger> map = new HashMap<>();
        long begin = System.currentTimeMillis();
        Process process = pb.start();
        if (process.waitFor() == 0) {
            dependency.totalTime = System.currentTimeMillis() - begin;
            dependency.totalTime /= 1000.0;
            String result = FileUtils.readFileToString(resultFile, CHARSET);
            if (result.contains("BUILD SUCCESS")) {
                Arrays.stream(result.split("\n")).forEach(str -> {
                    if (str.contains("Total time")) {
                        dependency.time = Double.valueOf(str.split(":")[1].replace("s", "").trim());
                    }
                    if (str.contains("Downloaded") && str.contains("maven-metadata.xml")) {
                        String[] split = str.split("/");
                        String group = split[split.length - 4];
                        if (map.containsKey(group)) {
                            map.get(group).incrementAndGet();
                        } else {
                            map.put(group, new AtomicInteger(1));
                        }
                    }
                });
            }
            map.entrySet().stream().sorted((o1, o2) -> {
                return -o1.getValue().get() + o2.getValue().get();
            }).forEach(item -> {
                dependency.metaDataList.add(item.getKey() + ":" + item.getValue());
            });
        }
        System.out.println(dependency.toString());
        FileUtils.deleteDirectory(workFile);
        return dependency;
    }

    /**
     * @author xupeng
     * @date 2018/10/9
     */
    public static class Dependency {
        public static final String UNKNOW = "unknow";
        public String artifactId = UNKNOW;
        public String groupId = UNKNOW;
        public String version = UNKNOW;
        public double time = 0;
        public double totalTime = 0;
        public List<String> metaDataList = new ArrayList<>();

        public Dependency(Element dependency) {
            groupId = dependency.element("groupId").getStringValue();
            artifactId = dependency.element("artifactId").getStringValue();
            Element versionEle = dependency.element("version");
            if (versionEle != null) {
                this.version = versionEle.getStringValue();
            }
        }

        public String key() {
            return groupId + "\t" + artifactId;
        }

        public String toString() {
            return key() + "\t" + time + "\t" + totalTime + "\t" + (metaDataList.size() == 0 ? "empty" : StringUtils.join(metaDataList, ";"));
        }
    }
}
