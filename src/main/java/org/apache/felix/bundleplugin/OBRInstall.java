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
import java.net.URI;

import org.apache.felix.obr.plugin.Config;
import org.apache.felix.obr.plugin.ObrUpdate;
import org.apache.felix.obr.plugin.ObrUtils;
import org.apache.felix.obr.plugin.PathFile;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;


/**
 * Installs bundle details in the local OBR repository
 * 
 * @goal install
 * @phase install
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class OBRInstall extends AbstractMojo
{
    /**
     * OBR Repository.
     * 
     * @parameter expression="${obrRepository}"
     */
    private String obrRepository;

    /**
     * Local Repository.
     * 
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    private ArtifactRepository localRepository;

    /**
     * The Maven project.
     * 
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;


    public void execute() throws MojoExecutionException
    {
        if ( "NONE".equalsIgnoreCase( obrRepository ) )
        {
            return;
        }

        Log log = getLog();
        ObrUpdate update;

        try
        {
            String mavenRepository = localRepository.getBasedir();
            String artifactPath = localRepository.pathOf( project.getArtifact() );
            String bundlePath = mavenRepository + File.separator + artifactPath;
            bundlePath = bundlePath.replace( '\\', '/' );

            URI repositoryXml = ObrUtils.findRepositoryXml( project.getBasedir(), mavenRepository, obrRepository );
            URI obrXml = ObrUtils.findObrXml( project.getResources() );

            String obrXmlPath = null;
            if ( null != obrXml )
            {
                obrXmlPath = obrXml.getPath();
            }

            Config userConfig = new Config();

            update = new ObrUpdate( new PathFile( repositoryXml.getPath() ), obrXmlPath, project, bundlePath,
                mavenRepository, userConfig, log );

            update.updateRepository();
        }
        catch ( Exception e )
        {
            log.warn( "Exception while updating OBR: " + e.getLocalizedMessage(), e );
        }
    }
}
