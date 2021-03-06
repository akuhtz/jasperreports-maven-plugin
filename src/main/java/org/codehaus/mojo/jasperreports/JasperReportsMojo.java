package org.codehaus.mojo.jasperreports;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License") you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.manager.CompilerManager;
import org.codehaus.plexus.compiler.manager.NoSuchCompilerException;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;
import org.sonatype.plexus.build.incremental.BuildContext;

import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.design.JRCompiler;
import net.sf.jasperreports.engine.xml.JRReportSaxParserFactory;

/**
 * Compiles JasperReports xml definition files.
 * <p>
 * Much of this was inspired by the JRAntCompileTask, while trying to make it slightly cleaner and
 * easier to use with Maven's mojo api.
 * </p>
 * 
 * @author gjoseph
 * @author Tom Schwenk
 * @goal compile-reports
 * @phase generate-sources
 * @requiresDependencyResolution compile
 */
public class JasperReportsMojo
    extends AbstractMojo
{

    /**
     * @component
     */
    private BuildContext buildContext;

    /**
     * @parameter expression="${project}
     */
    private MavenProject project;
    
    /**
     * The current build session instance. This is used for
     * toolchain manager API calls.
     *
     * @parameter default-value="${session}"
     * @required
     * @readonly
     */
    private MavenSession session;

    /**
     * This is where the generated java sources are stored.
     * 
     * @parameter expression="${project.build.directory}/jasperreports/java"
     */
    private File javaDirectory;

    /**
     * This is where the .jasper files are written.
     * 
     * @parameter expression="${jasper.outputDirectory}"
	 *            default-value="${project.build.directory}/generated-resources/jasper"
     * @required
     */
    private File outputDirectory;

    /**
     * This is where the xml report design files should be.
     * 
     * @parameter default-value="src/main/jasperreports"
     */
    private File sourceDirectory;

    /**
     * The extension of the source files to look for. Finds files with a .jrxml extension by
     * default.
     * 
     * @parameter default-value=".jrxml"
     */
    private String sourceFileExt;

    /**
     * The extension of the compiled report files. Creates files with a .jasper extension by
     * default.
     * 
     * @parameter default-value=".jasper"
     */
    private String outputFileExt;

    /**
     * Since the JasperReports compiler deletes the compiled classes, one might want to set this to
     * true, if they want to handle the generated java source in their application. Mind that this
     * will not work if the mojo is bound to the compile or any other later phase. (As one might
     * need to do if they use classes from their project in their report design)
     * 
     * @parameter default-value="false"
     * @deprecated There seems to be an issue with the compiler plugin so don't expect this to work
     *             yet - the dependencies will have disappeared.
     */
    private boolean keepJava;

    /**
     * Not used for now - just a TODO - the idea being that one might want to set this to false if
     * they want to handle the generated java source in their application.
     * 
     * @parameter default-value="true"
     * @deprecated Not implemented
     */
    private boolean keepSerializedObject;

    /**
     * Wether the xml design files must be validated.
     * 
     * @parameter default-value="true"
     */
    private boolean xmlValidation;

    /**
     * Uses the Javac compiler by default. This is different from the original JasperReports ant
     * task, which uses the JDT compiler by default.
     * 
     * @parameter default-value="org.codehaus.mojo.jasperreports.MavenJavacCompiler"
     */
    private String compiler;

    /**
     * @parameter expression="${project.compileClasspathElements}"
     */
    private List classpathElements;
    
    
    /**
     * Additional JRProperties
     * @parameter 
     * @since 1.0-beta-2
     */
    private Map additionalProperties = new HashMap();

    /**
     * Any additional classpath entry you might want to add to the JasperReports compiler. Not
     * recommended for general use, plugin dependencies should be used instead.
     * 
     * @parameter
     */
    private String additionalClasspath;

    /**
     * Plexus compiler manager.
     *
     * @component
     */
    private CompilerManager compilerManager;
    
    /** @component */
    private ToolchainManager toolchainManager;
    
    /**
     * The -source argument for the Java compiler.
     *
     * @parameter expression="${maven.compiler.source}" default-value="1.5"
     */
    protected String source;

    /**
     * The -target argument for the Java compiler.
     *
     * @parameter expression="${maven.compiler.target}" default-value="1.5"
     */
    protected String target;

    /**
     * The -encoding argument for the Java compiler.
     *
     * @parameter expression="${encoding}" default-value="${project.build.sourceEncoding}"
     */
    private String encoding;

    /**
     * Set to true to include debugging information in the compiled class files.
     *
     * @parameter expression="${maven.compiler.debug}" default-value="true"
     */
    private boolean debug = true;

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        getLog().debug( "javaDir = " + javaDirectory );
        getLog().debug( "sourceDirectory = " + sourceDirectory );
        getLog().debug( "sourceFileExt = " + sourceFileExt );
        getLog().info( "targetDirectory = " + outputDirectory );
        getLog().debug( "targetFileExt = " + outputFileExt );
        getLog().debug( "keepJava = " + keepJava );
        //getLog().debug("keepSerializedObject = " + keepSerializedObject);
        getLog().debug( "xmlValidation = " + xmlValidation );
        getLog().debug( "compiler = " + compiler );
        getLog().debug( "classpathElements = " + classpathElements );
        getLog().debug( "additionalClasspath = " + additionalClasspath );
        getLog().info( "compiler.source = " + source );
        getLog().info( "compiler.target = " + target );

        checkDir( javaDirectory, "Directory for generated java sources", true );
        checkDir( sourceDirectory, "Source directory", false );
        checkDir( outputDirectory, "Target directory", true );
		
		if (project != null) {
			getLog().info( "Adding outputDirectory as resource to project: " + outputDirectory);
			Resource resource = new Resource();
			resource.setDirectory( outputDirectory.getAbsolutePath() );
			project.addResource( resource );
		}
		
        // if this is an m2e configuration build then return immediately without doing any work
        if (project != null && buildContext.isIncremental() && !buildContext.hasDelta(project.getBasedir())) {
            return;
        }

        SourceMapping mapping = new SuffixMapping( sourceFileExt, outputFileExt );

        Set staleSources = scanSrcDir( mapping );
        if ( staleSources.isEmpty() )
        {
            getLog().info( "Nothing to compile - all Jasper reports are up to date" );
        }
        else
        {
            // actual compilation
            compile( staleSources, mapping );

            if ( keepJava )
            {
                project.addCompileSourceRoot( javaDirectory.getAbsolutePath() );
            }
        }
		
		getLog().info( "Adding outputDirectory to buildContext: " + outputDirectory);
		buildContext.refresh( outputDirectory );
    }

    protected void compile( Set files, SourceMapping mapping )
        throws MojoFailureException, MojoExecutionException
    {
        String classpath = buildClasspathString( classpathElements, additionalClasspath );
        getLog().debug( "buildClasspathString() = " + classpath );

        getLog().info( "Compiling " + files.size() + " report design files." );

        getLog().debug( "Set classloader" );
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader( getClassLoader( classLoader ) );

        JasperReportsContext jasperReportsContext = DefaultJasperReportsContext.getInstance();
        
        Map<String, String> backup = new HashMap<String, String>(); 
        backup.putAll(jasperReportsContext.getProperties());
        
//        JRProperties.backupProperties();

        try
        {
        	jasperReportsContext.setProperty( JRCompiler.COMPILER_CLASSPATH, classpath );
        	jasperReportsContext.setProperty( JRCompiler.COMPILER_TEMP_DIR, javaDirectory.getAbsolutePath() );
        	
        	jasperReportsContext.setProperty( JRCompiler.COMPILER_KEEP_JAVA_FILE, String.valueOf(keepJava) );
        	jasperReportsContext.setProperty( JRCompiler.COMPILER_PREFIX, compiler );
        	jasperReportsContext.setProperty( JRReportSaxParserFactory.COMPILER_XML_VALIDATION, String.valueOf(xmlValidation) );
            
            Compiler compilerMaven;
            
            String compilerId = "javac";

            getLog().debug( "Using compiler '" + compilerId + "'." );

            try
            {
                compilerMaven = compilerManager.getCompiler( compilerId );
            }
            catch ( NoSuchCompilerException e )
            {
                throw new MojoExecutionException( "No such compiler '" + e.getCompilerId() + "'." );
            }
            
            MavenJavacCompiler.init(getLog(), compilerMaven, debug, encoding, getToolchain(), source, target);

            for ( Iterator i = additionalProperties.keySet().iterator(); i.hasNext(); )
            {
                String key = (String) i.next();
                String value = (String) additionalProperties.get( key );
                jasperReportsContext.setProperty( key, value );
                getLog().debug( "Added property: " + key + ":" + value );
            }
            
            Iterator it = files.iterator();
            while ( it.hasNext() )
            {
                File src = (File) it.next();
                String srcName = getPathRelativeToRoot( src );
                try
                {
                    // get the single destination file
                    File dest = (File) mapping.getTargetFiles( outputDirectory, srcName ).iterator().next();

                    File destFileParent = dest.getParentFile();
                    if ( !destFileParent.exists() )
                    {
                        if ( destFileParent.mkdirs() )
                        {
                            getLog().debug( "Created directory " + destFileParent );
                        }
                        else
                        {
                            throw new MojoExecutionException( "Could not create directory " + destFileParent );
                        }
                    }
                    getLog().info( "Compiling report file: " + srcName );
                    JasperCompileManager.compileReportToFile( src.getAbsolutePath(), dest.getAbsolutePath() );
                }
                catch ( JRException e )
                {
                    throw new MojoExecutionException( "Error compiling report design : " + src, e );
                }
                catch ( InclusionScanException e )
                {
                    throw new MojoExecutionException( "Error compiling report design : " + src, e );
                }
            }
        }
        finally
        {
//            JRProperties.restoreProperties();
        	jasperReportsContext.getProperties().clear();
        	
        	jasperReportsContext.getProperties().putAll(backup);
        	
            if ( classLoader != null ) {
                Thread.currentThread().setContextClassLoader( classLoader );
            }
        }
        getLog().info( "Compiled " + files.size() + " report design files." );
    }

    /**
     * Determines source files to be compiled, based on the SourceMapping. No longer needs to be
     * recursive, since the SourceInclusionScanner handles that.
     * 
     * @param mapping
     * @return
     * @throws MojoExecutionException
     */
    protected Set scanSrcDir( SourceMapping mapping )
        throws MojoExecutionException
    {
        final int staleMillis = 0;

        SourceInclusionScanner scanner = new StaleSourceScanner( staleMillis );
        scanner.addSourceMapping( mapping );

        try
        {
            return scanner.getIncludedSources( sourceDirectory, outputDirectory );
        }
        catch ( InclusionScanException e )
        {
            throw new MojoExecutionException( "Error scanning source root: \'" + sourceDirectory + "\' "
                + "for stale files to recompile.", e );
        }
    }

    private String getPathRelativeToRoot( File file )
        throws MojoExecutionException
    {
        try
        {
            String root = this.sourceDirectory.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if ( !filePath.startsWith( root ) )
            {
                throw new MojoExecutionException( "File is not in source root ??? " + file );
            }
            return filePath.substring( root.length() + 1 );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Could not getCanonicalPath from file " + file, e );
        }
    }

    protected String buildClasspathString( List classpathElements, String additionalClasspath )
    {
        StringBuffer classpath = new StringBuffer();
        Iterator it = classpathElements.iterator();
        while ( it.hasNext() )
        {
            String cpElement = (String) it.next();
            classpath.append( cpElement );
            if ( it.hasNext() )
            {
                classpath.append( File.pathSeparator );
            }
        }
        if ( additionalClasspath != null )
        {
            if ( classpath.length() > 0 )
            {
                classpath.append( File.pathSeparator );
            }
            classpath.append( additionalClasspath );

        }
        return classpath.toString();
    }

    private void checkDir( File dir, String desc, boolean isTarget )
        throws MojoExecutionException
    {
        if ( dir.exists() && !dir.isDirectory() )
        {
            throw new MojoExecutionException( desc + " is not a directory : " + dir );
        }
        else if ( !dir.exists() && isTarget && !dir.mkdirs() )
        {
            throw new MojoExecutionException( desc + " could not be created : " + dir );
        }

        if ( isTarget && !dir.canWrite() )
        {
            throw new MojoExecutionException( desc + " is not writable : " + dir );
        }
    }

    private ClassLoader getClassLoader( ClassLoader classLoader )
        throws MojoExecutionException
    {
        List classpathURLs = new ArrayList();

        for ( int i = 0; i < classpathElements.size(); i++ )
        {
            String element = (String) classpathElements.get( i );
            try
            {
                File f = new File( element );
                URL newURL = f.toURI().toURL();
                classpathURLs.add( newURL );
                getLog().debug( "Added to classpath " + element );
            }
            catch ( Exception e )
            {
                throw new MojoExecutionException( "Error parsing classparh " + element + " " + e.getMessage() );
            }
        }

        if ( additionalClasspath != null && additionalClasspath.length() > 0 )
        {
            String[] elements = additionalClasspath.split( File.pathSeparator );
            for ( int i = 0; i < elements.length; i++ )
            {
                String element = elements[i];
                try
                {
                    File f = new File( element );
                    URL newURL = f.toURI().toURL();
                    classpathURLs.add( newURL );
                    getLog().debug( "Added to classpath " + element );
                }
                catch ( Exception e )
                {
                    throw new MojoExecutionException( "Error parsing classpath " + additionalClasspath + " "
                        + e.getMessage() );
                }
            }
        }

        URL[] urls = (URL[]) classpathURLs.toArray( new URL[classpathURLs.size()] );
        return new URLClassLoader( urls, classLoader );
    }
    
    //TODO remove the part with ToolchainManager lookup once we depend on
    //3.0.9 (have it as prerequisite). Define as regular component field then.
    private Toolchain getToolchain()
    {
        Toolchain tc = null;
        if ( toolchainManager != null )
        {
            tc = toolchainManager.getToolchainFromBuildContext( "jdk", session );
        }
        return tc;
    }

}
