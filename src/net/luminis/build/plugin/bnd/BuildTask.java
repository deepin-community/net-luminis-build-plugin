/*
 * Copyright (c) 2006, 2007 luminis
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * - Neither the name of the Luminis nor the names of its contributors may be
 *   used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
*/
package net.luminis.build.plugin.bnd;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.Manifest;
import org.apache.tools.ant.taskdefs.Manifest.Attribute;
import org.apache.tools.ant.taskdefs.Manifest.Section;
import org.apache.tools.ant.types.Path;

import aQute.lib.osgi.Analyzer;
import aQute.lib.osgi.Builder;
import aQute.lib.osgi.Jar;

public class BuildTask extends Task {
    Properties m_instructions = new Properties();
    File m_target;
    File m_outputDir;
    Path m_classpath;
    String m_filename;

    @SuppressWarnings("unchecked")
    public void execute() {
        try {
            Properties properties = new Properties();

            if (m_outputDir == null) {
                m_outputDir = (m_target == null) ? getProject().getBaseDir() : m_target.getParentFile().getAbsoluteFile();
            }

            String version = m_instructions.getProperty(Analyzer.BUNDLE_VERSION);

            if (version != null) {
                Pattern P_VERSION = Pattern.compile("([0-9]+(\\.[0-9])*)-(.*)");

                Matcher m = P_VERSION.matcher(version);

                if (m.matches()) {
                    version = m.group(1) + "." + m.group(3);
                }

                m_instructions.put(Analyzer.BUNDLE_VERSION, version);
                version = "-" + version;
            }
            else {
                version = "";
            }
            
            // if target is set: do not change the target
            if (m_target == null) {
                // if a filename is specified: use it, otherwise ignore and generate a filename
                if (m_filename != null) {
                    m_target = new File(m_outputDir, m_filename + ".jar");
                } else {
                    m_target = new File(m_outputDir, m_instructions.getProperty(Analyzer.BUNDLE_SYMBOLICNAME, m_instructions.getProperty(Analyzer.BUNDLE_NAME).replace(" ", "").trim()) + version + ".jar");
                }
            }

            properties.put(Analyzer.IMPORT_PACKAGE, "*");

            if (!m_instructions.containsKey(Analyzer.PRIVATE_PACKAGE)) {
                properties.put(Analyzer.EXPORT_PACKAGE, m_instructions.getProperty(Analyzer.BUNDLE_SYMBOLICNAME) + ".*");
            }

            properties.putAll(m_instructions);

            if (m_classpath == null)
                throw new BuildException("Classpath not set");
            if (m_classpath.list().length == 0)
                throw new BuildException("Classpath is empty");
            
            List<Jar> classpath = new ArrayList<Jar>(m_classpath.list().length);
            String classpathString = null; 
            for (String cpElement: m_classpath.list()) {
                File f = new File(cpElement);
                if (f.exists()) {
                    classpath.add(new Jar(f.getName(), f));
                    if (classpathString == null)
                        classpathString = f.toString();
                    else
                        classpathString += ":" + f;
                }
                else {
                    log("classpath element '" + cpElement + "' does not exist; ignored.", Project.MSG_WARN);
                }
            }
            // Log classpath only when in verbose mode
            log("Classpath: " + classpathString, Project.MSG_VERBOSE);

            Builder builder = new Builder();
            builder.setBase(getProject().getBaseDir());
            builder.setProperties(properties);
            builder.setClasspath(classpath.toArray(new Jar[classpath.size()]));

            builder.build();
            Jar jar = builder.getJar();
            jar.setName(m_target.getName());

            List errors = builder.getErrors();
            List warnings = builder.getWarnings();

            if (errors.size() > 0) {
                m_target.delete();
                for (Iterator e = errors.iterator(); e.hasNext();) {
                    String msg = (String) e.next();
                    getProject().log(msg);
                }
                throw new BuildException("Found errors, see log");
            }
            else {
                boolean exists = m_target.exists();
                boolean hasChanged = m_target.lastModified() <= jar.lastModified();
                if (!exists || hasChanged) {
                    jar.write(m_target);
                    if (exists) {
                        getProject().log("modified " + jar.getName());
                    }
                    else {
                        getProject().log("created " + jar.getName());
                    }
                }
            }
            for (Iterator w = warnings.iterator(); w.hasNext();) {
                String msg = (String) w.next();
                getProject().log(msg);
            }

        }
        catch (BuildException ex) {
            throw ex;
        }
        catch (Exception e) {
            throw new BuildException("Unknown error occurred", e);
        }
    }

    public void setClasspathRefId(String value) {
        if (value == null) {
            throw new BuildException("ClasspathRefId value can not be null");
        }
        else if (getProject().getReference(value) == null) {
            throw new BuildException("ClasspathRefId value [" + value + "] not found in project");
        }
        m_classpath = ((Path) getProject().getReference(value));
    }

    /**
     * Adds a path to the classpath.
     * @return a class path to be configured
     */
    public Path createClasspath() {
        if (m_classpath == null) {
            m_classpath = new Path(getProject());
        }
        return m_classpath.createPath();
    }
    
    /**
     * Set the classpath to be used for this compilation.
     *
     * @param classpath an Ant Path object containing the compilation classpath.
     */
    public void setClasspath(Path classpath) {
        if (m_classpath == null) {
            m_classpath = classpath;
        } else {
            m_classpath.append(classpath);
        }
    }

    public void setTarget(File value) {
        m_target = value;
    }

    public void setOutputDir(File value) {
        m_outputDir = value;
    }

    public void setBundleClassPath(String value) {
        m_instructions.put(Analyzer.BUNDLE_CLASSPATH, value);
    }

    public void setBundleCopyright(String value) {
        m_instructions.put(Analyzer.BUNDLE_COPYRIGHT, value);
    }

    public void setBundleDescription(String value) {
        m_instructions.put(Analyzer.BUNDLE_DESCRIPTION, value);
    }

    public void setBundleName(String value) {
        m_instructions.put(Analyzer.BUNDLE_NAME, value);
    }

    public void setBundleNativeCode(String value) {
        m_instructions.put(Analyzer.BUNDLE_NATIVECODE, value);
    }

    public void setExportPackage(String value) {
        m_instructions.put(Analyzer.EXPORT_PACKAGE, value);
    }

    public void setExportService(String value) {
        m_instructions.put(Analyzer.EXPORT_SERVICE, value);
    }

    public void setImportPackage(String value) {
        m_instructions.put(Analyzer.IMPORT_PACKAGE, value);
    }

    public void setDynamicImportPackage(String value) {
        m_instructions.put(Analyzer.DYNAMICIMPORT_PACKAGE, value);
    }

    public void setImportService(String value) {
        m_instructions.put(Analyzer.IMPORT_SERVICE, value);
    }

    public void setBundleVendor(String value) {
        m_instructions.put(Analyzer.BUNDLE_VENDOR, value);
    }

    public void setBundleVersion(String value) {
        m_instructions.put(Analyzer.BUNDLE_VERSION, value);
    }

    public void setBundleDocURL(String value) {
        m_instructions.put(Analyzer.BUNDLE_DOCURL, value);
    }

    public void setBundleContactAddress(String value) {
        m_instructions.put(Analyzer.BUNDLE_CONTACTADDRESS, value);
    }

    public void setBundleActivator(String value) {
        m_instructions.put(Analyzer.BUNDLE_ACTIVATOR, value);
    }

    public void setBundleRequiredExecutionEnvironment(String value) {
        m_instructions.put(Analyzer.BUNDLE_REQUIREDEXECUTIONENVIRONMENT, value);
    }

    public void setBundleSymbolicName(String value) {
        m_instructions.put(Analyzer.BUNDLE_SYMBOLICNAME, value);
    }

    public void setBundleLocalization(String value) {
        m_instructions.put(Analyzer.BUNDLE_LOCALIZATION, value);
    }

    public void setRequireBundle(String value) {
        m_instructions.put(Analyzer.REQUIRE_BUNDLE, value);
    }

    public void setFragmentHost(String value) {
        m_instructions.put(Analyzer.FRAGMENT_HOST, value);
    }

    /**
     * @deprecated	with no replacement: Bnd always uses Bundle-ManifestVersion 2
     */
    public void setBundleManifestVersion(String value) {
        log("attribute bundleManifestVersion is deprecated and ignored (bundle manifest version is always '2')");
    }

    public void setServiceComponent(String value) {
        m_instructions.put(Analyzer.SERVICE_COMPONENT, value);
    }

    public void setBundleLicense(String value) {
        m_instructions.put(Analyzer.BUNDLE_LICENSE, value);
    }

    public void setPrivatePackage(String value) {
        m_instructions.put(Analyzer.PRIVATE_PACKAGE, value);
    }

    public void setIgnorePackage(String value) {
        m_instructions.put(Analyzer.IGNORE_PACKAGE, value);
    }

    public void setIncludeResource(String value) {
        if (value == null) {
            throw new BuildException("Resource to include can not be null");
        }
        else if (value.length() == 0) {
            log("empty string passed to the include resource, ignored as BND uses this to include the currect directory", Project.MSG_VERBOSE);
            return;
        }
        else {
            m_instructions.put(Analyzer.INCLUDE_RESOURCE, value);
        }
    }

    /**
     * Set whether to include the "Include-Resource" header in the manifest. Default is true.
     * Note that this header is quite useless, and as it might confuse people, especially when
     * used with a local path that differs from the include path, it can be suppressed by this
     * task attribute.
     */
    public void setIncludeIncludeResourceHeader(boolean include) {
        if (! include)
            m_instructions.put(Analyzer.REMOVE_HEADERS, Analyzer.INCLUDE_RESOURCE);
        // default is do include
    }
    
    public void setAdditionalManifest(File value) {
        m_instructions.put(Analyzer.INCLUDE, value.getAbsolutePath());
    }
    
    /**
     * Allows inline manifest declaration to provide extra manifest entries to bnd.
     * This simplifies the ant call as the user does not have create tempfiles in Ant. Only a
     * manifest ant element needs to be specified.
     * 
     * Note that only main attributes can be passed.
     *
     * @param newManifest an embedded manifest element
     * @throws IOException if an error occurs during the parsing of the manifest
     */
    @SuppressWarnings("unchecked")
    public void addConfiguredManifest(Manifest newManifest) throws IOException {
        if (newManifest == null) {
            throw new BuildException("manifest can not be null");
        }
        // Create temp file.
        File temp = File.createTempFile("bnd", ".tmp");
    
        // Delete temp file when program exits.
        temp.deleteOnExit();

        PrintWriter tempWriter = new PrintWriter(temp);
        Section main = newManifest.getMainSection();
        Enumeration e = main.getAttributeKeys();
        while (e.hasMoreElements()) {
            Attribute attr = main.getAttribute((String) e.nextElement());
            tempWriter.println(attr.getName() + "=" + attr.getValue());
        }
        tempWriter.close();
        setAdditionalManifest(temp);
    }
    
    public void setFilename(String filename) {
        m_filename = filename;
    }
}
