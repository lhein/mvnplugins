package org.fusesource.mvnplugins.notices.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.DefaultModelReader;
import org.apache.maven.model.io.DefaultModelWriter;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.model.io.ModelWriter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.downloader.Downloader;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.apache.maven.shared.invoker.PrintStreamHandler;
import org.codehaus.plexus.util.StringUtils;

public class DependencyPom {
    
    private MavenProject project;
    private Downloader downloader;
    private Model model;
    private String projectVersion;
    private File file;
    private ArtifactRepository localRepository;
    private List<ArtifactRepository> remoteArtifactRepositories;
    private List<Dependency> extraDependencies;

    public DependencyPom(MavenProject project, Downloader downloader, ArtifactRepository localRepository, List<ArtifactRepository> remoteArtifactRepositories, String extraDependencies, String defaultParent) {
        this.project = project;
        this.downloader = downloader;
        this.localRepository = localRepository;
        this.remoteArtifactRepositories = remoteArtifactRepositories;
        
        this.extraDependencies = new ArrayList<Dependency>();
        if (extraDependencies != null) {
            for (String depStr : extraDependencies.split(",")) {
                String[] depStrSplit = depStr.split(":");
                Dependency dep = new Dependency();
                dep.setGroupId(depStrSplit[0]);
                dep.setArtifactId(depStrSplit[1]);
                dep.setVersion(depStrSplit[2]);
                this.extraDependencies.add(dep);
            }
        }
        
        model = project.getOriginalModel();
        model.setPackaging("jar");
        model.setProfiles(null);
        model.setBuild(null);
        model.setArtifactId(model.getArtifactId() + "-dependencies");       
        
        projectVersion = project.getVersion();
        model.setVersion(projectVersion);
        
        setParent(defaultParent);
        
        Build build = new Build();
        model.setBuild(build);
    }
    
    private void setParent(String defaultParent) {
        // hack to go from maven coordinate string to Parent obj. TODO any Maven util class to do this?
        String[] strings = defaultParent.split(":");
        model.getParent().setGroupId(strings[0]);
        model.getParent().setArtifactId(strings[1]);        
        if ("VERSION".equals(strings[2])) {
            model.getParent().setVersion(projectVersion);            
        } else {
            model.getParent().setVersion(strings[2]);
        }
    }

    public void addPlugin(Plugin plugin) {
        model.getBuild().addPlugin(plugin);        
    }

    public void generatePom(String repositories, String targetDir) throws IOException {
        List<Dependency> loadDependenciesFromRepos = loadDependenciesFromRepos(repositories);
        loadDependenciesFromRepos.addAll(extraDependencies);
        model.setDependencies(loadDependenciesFromRepos);
        
        file = new File(targetDir, "dependency-pom.xml");
        if (file.exists()) {
            file.delete();
        }
        ModelWriter writer = new DefaultModelWriter();
        writer.write(file, null, model);
    }
    
    public File buildPom() throws MavenInvocationException {
        if (file != null) {
            InvocationRequest request = new DefaultInvocationRequest();
            request.setPomFile(file);
            request.setBaseDirectory(file.getParentFile());
            request.setLocalRepositoryDirectory(new File(localRepository.getBasedir()));
            request.setGoals(Collections.singletonList("package"));
            
            PrintStream invokerLog = null;
            try {
                invokerLog = new PrintStream(new FileOutputStream(file.getAbsoluteFile().toString() + ".log"));                            
                request.setOutputHandler(new PrintStreamHandler(invokerLog, false));            
            
                Invoker invoker = new DefaultInvoker();
                invoker.execute(request);
            } catch (FileNotFoundException e) {
            } finally {
                if (invokerLog != null) {
                    invokerLog.close();    
                }
            }
            
            return new File(project.getBasedir() + File.separator + "target" 
                    + File.separator + "target" + File.separator + model.getArtifactId() + "-" + projectVersion + ".jar");           
        }
        return null;
    }
    
    private List<Dependency> loadDependenciesFromRepos(String repositories) {
        if (repositories.contains(",")) {            
            List<Dependency> deps = new ArrayList<Dependency>();
            for (String repo : repositories.split(",")) {
                deps.addAll(loadDependenciesFromRepo(new File(repo)));
            }
            return deps;
        } else {
            return loadDependenciesFromRepo(new File(repositories));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Dependency> loadDependenciesFromRepo(File repo) {
        Iterator<File> jars = FileUtils.listFiles(repo, new String[] { "jar" }, true).iterator();
        List<Dependency> deps = new ArrayList<Dependency>();
        int currentJarNum = 0;
        while (jars.hasNext()) {
            File jar = jars.next();
            
            // ArtifactResolver leaves file handles around so need to clean up
            // or we will run out of file descriptors
            if (currentJarNum++ % 100 == 0) {
                System.gc();    
                System.runFinalization();                
            }
            Dependency dependencyFromJar = dependencyFromJar(jar, repo);
            if (dependencyFromJar != null) {
                deps.add(dependencyFromJar);
            }
        }
        return deps;
    }    
    
    private Model getPom(File jar) {
        try {
            ZipFile zip = new ZipFile(jar);
            FileInputStream fis = new FileInputStream(jar);
            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fis));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith("META-INF") && entry.getName().endsWith("pom.xml")) {
                    ModelReader reader = new DefaultModelReader();
                    InputStream inputStream = zip.getInputStream(entry);
                    Model pom = reader.read(inputStream, null);
                    inputStream.close();
                    return pom;
                }
            }
            zis.close();
            fis.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private Properties getPomProperties(File jar) {
        try {
            ZipFile zip = new ZipFile(jar);
            FileInputStream fis = new FileInputStream(jar);
            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fis));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith("META-INF") && entry.getName().endsWith("pom.properties")) {
                    Properties props = new Properties();
                    InputStream inputStream = zip.getInputStream(entry);
                    props.load(inputStream);
                    inputStream.close();
                    return props;
                }
            }
            zis.close();
            fis.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private Dependency dependencyFromJar(File jar, File repo) {
        Model pom = getPom(jar);
        Properties props = getPomProperties(jar);
        Dependency dep = new Dependency();
        if (props != null) {   
            dep.setArtifactId(props.getProperty("artifactId"));
            dep.setGroupId(props.getProperty("groupId"));
            dep.setVersion(props.getProperty("version"));
        } else if (pom != null) {            
            dep.setArtifactId(pom.getArtifactId());
            dep.setGroupId(pom.getGroupId());
            dep.setVersion(pom.getVersion());
        } else { 
            String version = jar.getParentFile().getName();
            String artifactId = jar.getParentFile().getParentFile().getName();
            String groupIdSep = StringUtils.replace(jar.getParentFile().getParentFile().getParentFile().getPath(), repo.getPath(), "");
            String groupId = groupIdSep.replace(File.separatorChar, '.');
            groupId = groupId.startsWith(".") ? groupId.substring(1) : groupId;
            groupId = groupId.endsWith(".") ? groupId.substring(0, groupId.length() - 1) : groupId;
            dep.setArtifactId(artifactId);
            dep.setGroupId(groupId);
            dep.setVersion(version);
        }       
        
        try {
            downloader.download( dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), localRepository, remoteArtifactRepositories );
        } catch (Exception e) {
            e.printStackTrace();
            dep = null;            
        }         
        return dep;
    }
}
