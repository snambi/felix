/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.bundleplugin;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.jar.Manifest;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import aQute.bnd.osgi.Analyzer;
import aQute.bnd.osgi.Builder;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.Resource;


/**
 * Generate an OSGi manifest for this project
 * 
 * @goal manifest
 * @phase process-classes
 * @requiresDependencyResolution test
 * @threadSafe
 */
public class ManifestPlugin extends BundlePlugin
{
    /**
     * When true, generate the manifest by rebuilding the full bundle in memory 
     *
     * @parameter expression="${rebuildBundle}"
     */
    protected boolean rebuildBundle;


    @Override
    protected void execute( MavenProject project, Map instructions, Properties properties, Jar[] classpath )
        throws MojoExecutionException
    {
        Manifest manifest;
        
        // FIXME: time1
    	long t1 = Calendar.getInstance().getTimeInMillis();
    	
    	String value = System.getProperty("bundle.isParallel");
    	if( value != null && !value.trim().equals("")){
    		if( value.toLowerCase().equals("true")){
    			setParallel(true);
    		}else{
    			setParallel(false);
    		}
    	}
    	
        try
        {
            manifest = getManifest( project, instructions, properties, classpath, isParallel() );
        }
        catch ( FileNotFoundException e )
        {
            throw new MojoExecutionException( "Cannot find " + e.getMessage()
                + " (manifest goal must be run after compile phase)", e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error trying to generate Manifest", e );
        }
        catch ( MojoFailureException e )
        {
            getLog().error( e.getLocalizedMessage() );
            throw new MojoExecutionException( "Error(s) found in manifest configuration", e );
        }
        catch ( Exception e )
        {
            getLog().error( "An internal error occurred", e );
            throw new MojoExecutionException( "Internal error in maven-bundle-plugin", e );
        }

        File outputFile = new File( manifestLocation, "MANIFEST.MF" );

        try
        {
            writeManifest( manifest, outputFile );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error trying to write Manifest to file " + outputFile, e );
        }
        
        // FIXME: time2
    	long t2 = Calendar.getInstance().getTimeInMillis();
    	System.out.println("manifest.execute() " + (t2-t1) );
    }


    public Manifest getManifest( MavenProject project, 
    								Jar[] classpath, 
    								boolean isParallel 
    							) 
    									throws IOException, MojoFailureException,MojoExecutionException, Exception
    {
        return getManifest( project, new LinkedHashMap(), new Properties(), classpath , isParallel );
    }


    public Manifest getManifest( MavenProject project, 
    								Map instructions, 
    								Properties properties, 
    								Jar[] classpath, 
    								boolean isParallel )
    										throws IOException, MojoFailureException, MojoExecutionException, Exception
    {
    	
    	// FIXME: time1
    	long t1 = Calendar.getInstance().getTimeInMillis();
        
    	Analyzer analyzer = getAnalyzer( project, instructions, properties, classpath, isParallel );
        boolean hasErrors = reportErrors( "Manifest " + project.getArtifact(), analyzer );
        if ( hasErrors )
        {
            String failok = analyzer.getProperty( "-failok" );
            if ( null == failok || "false".equalsIgnoreCase( failok ) )
            {
                throw new MojoFailureException( "Error(s) found in manifest configuration" );
            }
        }

        Jar jar = analyzer.getJar();

        if ( unpackBundle )
        {
            File outputFile = getOutputDirectory();
            for ( Entry<String, Resource> entry : jar.getResources().entrySet() )
            {
                File entryFile = new File( outputFile, entry.getKey() );
                if ( !entryFile.exists() || entry.getValue().lastModified() == 0 )
                {
                    entryFile.getParentFile().mkdirs();
                    OutputStream os = new FileOutputStream( entryFile );
                    entry.getValue().write( os );
                    os.close();
                }
            }
        }

        Manifest manifest = jar.getManifest();

        // cleanup...
        analyzer.close();
        
        // FIXME: time2
        long t2 = Calendar.getInstance().getTimeInMillis();
        System.out.println("getManifest " + ( t2-t1 ));

        return manifest;
    }


    protected Analyzer getAnalyzer( MavenProject project, Jar[] classpath, boolean isParallel ) throws IOException, MojoExecutionException,
        Exception
    {
        return getAnalyzer( project, new LinkedHashMap(), new Properties(), classpath, isParallel );
    }


    /**
     * Generates and merges the Manifest.MF file.
     * 
     * @param project
     * @param instructions
     * @param properties
     * @param classpath
     * @param isParallel
     * @return
     * @throws IOException
     * @throws MojoExecutionException
     * @throws Exception
     */
    protected Analyzer getAnalyzer( MavenProject project, 
    								Map instructions, 
    								Properties properties, 
    								Jar[] classpath,
    								boolean isParallel)
    										throws IOException, MojoExecutionException, Exception
    {
        if ( rebuildBundle && supportedProjectTypes.contains( project.getArtifact().getType() ) )
        {
        	// TODO: enable parallelization
            return buildOSGiBundle( project, instructions, properties, classpath );
        }

        File file = project.getArtifact().getFile();
        if ( file == null )
        {
            file = getOutputDirectory();
        }

        if ( !file.exists() )
        {
            if ( file.equals( getOutputDirectory() ) )
            {
                file.mkdirs();
            }
            else
            {
                throw new FileNotFoundException( file.getPath() );
            }
        }

        Builder osgibuilder = getOSGiBuilder( project, instructions, properties, classpath );

        osgibuilder.setJar( file );
        osgibuilder.setParallel(isParallel);

        // calculateExportsFromContents when we have no explicit instructions defining
        // the contents of the bundle *and* we are not analyzing the output directory,
        // otherwise fall-back to addMavenInstructions approach

        boolean isOutputDirectory = file.equals( getOutputDirectory() );

        if ( osgibuilder.getProperty( Analyzer.EXPORT_PACKAGE ) == null
            && osgibuilder.getProperty( Analyzer.EXPORT_CONTENTS ) == null
            && osgibuilder.getProperty( Analyzer.PRIVATE_PACKAGE ) == null && !isOutputDirectory )
        {
            String export = calculateExportsFromContents( osgibuilder.getJar() );
            osgibuilder.setProperty( Analyzer.EXPORT_PACKAGE, export );
        }

        addMavenInstructions( project, osgibuilder );

        // if we spot Embed-Dependency and the bundle is "target/classes", assume we need to rebuild
        if ( osgibuilder.getProperty( DependencyEmbedder.EMBED_DEPENDENCY ) != null && isOutputDirectory )
        {
            osgibuilder.build();
        }
        else
        {
            osgibuilder.mergeManifest( osgibuilder.getJar().getManifest() );
            osgibuilder.getJar().setManifest( osgibuilder.calcManifest() );
        }

        mergeMavenManifest( project, osgibuilder );

        return osgibuilder;
    }


    public static void writeManifest( Manifest manifest, File outputFile ) throws IOException
    {
        outputFile.getParentFile().mkdirs();

        FileOutputStream os;
        os = new FileOutputStream( outputFile );
        try
        {
            Jar.writeManifest( manifest, os );
        }
        finally
        {
            try
            {
                os.close();
            }
            catch ( IOException e )
            {
                // nothing we can do here
            }
        }
    }


    /*
     * Patched version of bnd's Analyzer.calculateExportsFromContents
     */
    public static String calculateExportsFromContents( Jar bundle )
    {
        String ddel = "";
        StringBuffer sb = new StringBuffer();
        Map<String, Map<String, Resource>> map = bundle.getDirectories();
        for ( Iterator<Entry<String, Map<String, Resource>>> i = map.entrySet().iterator(); i.hasNext(); )
        {
            //----------------------------------------------------
            // should also ignore directories with no resources
            //----------------------------------------------------
            Entry<String, Map<String, Resource>> entry = i.next();
            if ( entry.getValue() == null || entry.getValue().isEmpty() )
                continue;
            //----------------------------------------------------
            String directory = entry.getKey();
            if ( directory.equals( "META-INF" ) || directory.startsWith( "META-INF/" ) )
                continue;
            if ( directory.equals( "OSGI-OPT" ) || directory.startsWith( "OSGI-OPT/" ) )
                continue;
            if ( directory.equals( "/" ) )
                continue;

            if ( directory.endsWith( "/" ) )
                directory = directory.substring( 0, directory.length() - 1 );

            directory = directory.replace( '/', '.' );
            sb.append( ddel );
            sb.append( directory );
            ddel = ",";
        }
        return sb.toString();
    }
}
