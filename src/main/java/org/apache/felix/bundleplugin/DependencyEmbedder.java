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
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;

import aQute.lib.header.OSGiHeader;
import aQute.lib.osgi.Analyzer;
import aQute.lib.osgi.Instruction;


/**
 * Add BND directives to embed selected dependencies inside a bundle
 * 
 * @author stuart.mcculloch@jayway.net (Stuart McCulloch)
 */
public final class DependencyEmbedder
{
    public static final String EMBED_DEPENDENCY = "Embed-Dependency";
    public static final String EMBED_DIRECTORY = "Embed-Directory";
    public static final String EMBED_STRIP_GROUP = "Embed-StripGroup";
    public static final String EMBED_STRIP_VERSION = "Embed-StripVersion";
    public static final String EMBED_TRANSITIVE = "Embed-Transitive";

    private String m_embedDirectory;
    private String m_embedStripGroup;
    private String m_embedStripVersion;

    /**
     * Dependency artifacts.
     */
    private final Collection m_dependencyArtifacts;

    /**
     * Inlined artifacts.
     */
    private final Collection m_inlinedArtifacts;

    /**
     * Embedded artifacts.
     */
    private final Collection m_embeddedArtifacts;


    public DependencyEmbedder( Collection dependencyArtifacts )
    {
        m_dependencyArtifacts = dependencyArtifacts;

        m_inlinedArtifacts = new HashSet();
        m_embeddedArtifacts = new HashSet();
    }


    public void processHeaders( Properties properties ) throws MojoExecutionException
    {
        m_inlinedArtifacts.clear();
        m_embeddedArtifacts.clear();

        String embedDependencyHeader = properties.getProperty( EMBED_DEPENDENCY );
        if ( null != embedDependencyHeader && embedDependencyHeader.length() > 0 )
        {
            m_embedDirectory = properties.getProperty( EMBED_DIRECTORY );
            m_embedStripGroup = properties.getProperty( EMBED_STRIP_GROUP, "true" );
            m_embedStripVersion = properties.getProperty( EMBED_STRIP_VERSION );

            Map embedInstructions = OSGiHeader.parseHeader( embedDependencyHeader );
            processEmbedInstructions( embedInstructions );

            for ( Iterator i = m_inlinedArtifacts.iterator(); i.hasNext(); )
            {
                inlineDependency( properties, ( Artifact ) i.next() );
            }
            for ( Iterator i = m_embeddedArtifacts.iterator(); i.hasNext(); )
            {
                embedDependency( properties, ( Artifact ) i.next() );
            }
        }
    }

    protected static abstract class DependencyFilter
    {
        private final Instruction m_instruction;
        private final String m_defaultValue;


        public DependencyFilter( String expression )
        {
            this( expression, "" );
        }


        public DependencyFilter( String expression, String defaultValue )
        {
            m_instruction = Instruction.getPattern( expression );
            m_defaultValue = defaultValue;
        }


        public void filter( Collection dependencies )
        {
            for ( Iterator i = dependencies.iterator(); i.hasNext(); )
            {
                if ( false == matches( ( Artifact ) i.next() ) )
                {
                    i.remove();
                }
            }
        }


        abstract boolean matches( Artifact dependency );


        private boolean matches( String text )
        {
            if ( null == text )
            {
                text = m_defaultValue;
            }

            boolean result = m_instruction.matches( text );
            return m_instruction.isNegated() ? !result : result;
        }
    }


    private void processEmbedInstructions( Map embedInstructions ) throws MojoExecutionException
    {
        DependencyFilter filter;
        for ( Iterator clauseIterator = embedInstructions.entrySet().iterator(); clauseIterator.hasNext(); )
        {
            boolean inline = false;

            // must use a fresh *modifiable* collection for each unique clause
            Collection filteredDependencies = new HashSet( m_dependencyArtifacts );

            // CLAUSE: REGEXP --> { ATTRIBUTE MAP }
            Map.Entry clause = ( Map.Entry ) clauseIterator.next();

            filter = new DependencyFilter( ( String ) clause.getKey() )
            {
                boolean matches( Artifact dependency )
                {
                    return super.matches( dependency.getArtifactId() );
                }
            };

            // FILTER ON MAIN CLAUSE
            filter.filter( filteredDependencies );

            for ( Iterator attrIterator = ( ( Map ) clause.getValue() ).entrySet().iterator(); attrIterator.hasNext(); )
            {
                // ATTRIBUTE: KEY --> REGEXP
                Map.Entry attr = ( Map.Entry ) attrIterator.next();

                if ( "groupId".equals( attr.getKey() ) )
                {
                    filter = new DependencyFilter( ( String ) attr.getValue() )
                    {
                        boolean matches( Artifact dependency )
                        {
                            return super.matches( dependency.getGroupId() );
                        }
                    };
                }
                else if ( "artifactId".equals( attr.getKey() ) )
                {
                    filter = new DependencyFilter( ( String ) attr.getValue() )
                    {
                        boolean matches( Artifact dependency )
                        {
                            return super.matches( dependency.getArtifactId() );
                        }
                    };
                }
                else if ( "version".equals( attr.getKey() ) )
                {
                    filter = new DependencyFilter( ( String ) attr.getValue() )
                    {
                        boolean matches( Artifact dependency )
                        {
                            try
                            {
                                // use the symbolic version if available (ie. 1.0.0-SNAPSHOT)
                                return super.matches( dependency.getSelectedVersion().toString() );
                            }
                            catch ( Exception e )
                            {
                                return super.matches( dependency.getVersion() );
                            }
                        }
                    };
                }
                else if ( "scope".equals( attr.getKey() ) )
                {
                    filter = new DependencyFilter( ( String ) attr.getValue(), "compile" )
                    {
                        boolean matches( Artifact dependency )
                        {
                            return super.matches( dependency.getScope() );
                        }
                    };
                }
                else if ( "type".equals( attr.getKey() ) )
                {
                    filter = new DependencyFilter( ( String ) attr.getValue(), "jar" )
                    {
                        boolean matches( Artifact dependency )
                        {
                            return super.matches( dependency.getType() );
                        }
                    };
                }
                else if ( "classifier".equals( attr.getKey() ) )
                {
                    filter = new DependencyFilter( ( String ) attr.getValue() )
                    {
                        boolean matches( Artifact dependency )
                        {
                            return super.matches( dependency.getClassifier() );
                        }
                    };
                }
                else if ( "optional".equals( attr.getKey() ) )
                {
                    filter = new DependencyFilter( ( String ) attr.getValue(), "false" )
                    {
                        boolean matches( Artifact dependency )
                        {
                            return super.matches( "" + dependency.isOptional() );
                        }
                    };
                }
                else if ( "inline".equals( attr.getKey() ) )
                {
                    inline = Boolean.valueOf( ( String ) attr.getValue() ).booleanValue();

                    continue;
                }
                else
                {
                    throw new MojoExecutionException( "Unexpected attribute " + attr.getKey() );
                }

                // FILTER ON EACH ATTRIBUTE
                filter.filter( filteredDependencies );
            }

            if ( inline )
            {
                m_inlinedArtifacts.addAll( filteredDependencies );
            }
            else
            {
                m_embeddedArtifacts.addAll( filteredDependencies );
            }
        }

        // remove any inlined artifacts from the embedded list
        m_embeddedArtifacts.removeAll( m_inlinedArtifacts );
    }


    private void embedDependency( Properties properties, Artifact dependency )
    {
        File sourceFile = dependency.getFile();
        if ( null != sourceFile && sourceFile.exists() )
        {
            String embedDirectory = m_embedDirectory;
            if ( "".equals( embedDirectory ) || ".".equals( embedDirectory ) )
            {
                embedDirectory = null;
            }

            if ( false == Boolean.valueOf( m_embedStripGroup ).booleanValue() )
            {
                embedDirectory = new File( embedDirectory, dependency.getGroupId() ).getPath();
            }

            File targetFile;
            if ( Boolean.valueOf( m_embedStripVersion ).booleanValue() )
            {
                String extension = dependency.getArtifactHandler().getExtension();
                if ( extension != null )
                {
                    targetFile = new File( embedDirectory, dependency.getArtifactId() + "." + extension );
                }
                else
                {
                    targetFile = new File( embedDirectory, dependency.getArtifactId() );
                }
            }
            else
            {
                targetFile = new File( embedDirectory, sourceFile.getName() );
            }

            String targetFilePath = targetFile.getPath();

            // replace windows backslash with a slash
            if ( File.separatorChar != '/' )
            {
                targetFilePath = targetFilePath.replace( File.separatorChar, '/' );
            }

            String bundleClassPath = properties.getProperty( Analyzer.BUNDLE_CLASSPATH );
            String includeResource = properties.getProperty( Analyzer.INCLUDE_RESOURCE );

            if ( null == includeResource )
            {
                includeResource = "";
            }
            else if ( includeResource.length() > 0 )
            {
                includeResource += ",";
            }

            includeResource += targetFilePath;
            includeResource += "=";
            includeResource += sourceFile;

            if ( null == bundleClassPath )
            {
                bundleClassPath = ".,";
            }
            else if ( bundleClassPath.length() > 0 )
            {
                bundleClassPath += ",";
            }

            bundleClassPath += targetFilePath;

            properties.setProperty( Analyzer.BUNDLE_CLASSPATH, bundleClassPath );
            properties.setProperty( Analyzer.INCLUDE_RESOURCE, includeResource );
        }
    }


    private void inlineDependency( Properties properties, Artifact dependency )
    {
        File sourceFile = dependency.getFile();
        if ( null != sourceFile && sourceFile.exists() )
        {
            String includeResource = properties.getProperty( Analyzer.INCLUDE_RESOURCE );

            if ( null == includeResource )
            {
                includeResource = "";
            }
            else if ( includeResource.length() > 0 )
            {
                includeResource += ",";
            }

            includeResource += "@";
            includeResource += sourceFile;

            properties.setProperty( Analyzer.INCLUDE_RESOURCE, includeResource );
        }
    }


    public Collection getInlinedArtifacts()
    {
        return m_inlinedArtifacts;
    }


    public Collection getEmbeddedArtifacts()
    {
        return m_embeddedArtifacts;
    }
}
