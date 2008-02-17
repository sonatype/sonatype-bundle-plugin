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
package org.apache.felix.obr.plugin;


import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;


/**
 * Deploys bundle details to a remote OBR repository (command-line goal)
 * 
 * @requiresProject false
 * @goal deploy-file
 * @phase deploy
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public final class ObrDeployFile extends AbstractFileMojo
{
    /**
     * When true, ignore remote locking.
     * 
     * @parameter expression="${ignoreLock}"
     */
    private boolean ignoreLock;

    /**
     * OBR Repository.
     * 
     * @parameter expression="${obrRepository}"
     */
    private String obrRepository;

    /**
     * Project types which this plugin supports.
     *
     * @parameter
     */
    private List supportedProjectTypes = Arrays.asList( new String[]
        { "jar", "bundle" } );

    /**
     * Remote repository id, used to lookup authentication settings.
     *
     * @parameter expression="${repositoryId}" default-value="remote-repository"
     * @required
     */
    private String repositoryId;

    /**
     * Remote OBR repository URL, where the bundle details are to be uploaded.
     *
     * @parameter expression="${url}"
     * @required
     */
    private String url;

    /**
     * Optional public URL where the bundle has been deployed.
     *
     * @parameter expression="${bundleUrl}"
     */
    private String bundleUrl;

    /**
     * Local Repository.
     * 
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    private ArtifactRepository localRepository;

    /**
     * Local Maven settings.
     * 
     * @parameter expression="${settings}"
     * @required
     * @readonly
     */
    private Settings settings;

    /**
     * The Wagon manager.
     * 
     * @component
     */
    private WagonManager m_wagonManager;


    public void execute() throws MojoExecutionException
    {
        MavenProject project = getProject();

        if ( !supportedProjectTypes.contains( project.getPackaging() ) )
        {
            getLog().info( "Ignoring packaging type " + project.getPackaging() );
            return;
        }
        else if ( "NONE".equalsIgnoreCase( obrRepository ) )
        {
            getLog().info( "OBR update disabled (enable with -DobrRepository)" );
            return;
        }

        URI tempURI = ObrUtils.findRepositoryXml( "", obrRepository );
        String repositoryName = new File( tempURI.getPath() ).getName();

        Log log = getLog();
        ObrUpdate update;

        RemoteFileManager remoteFile = new RemoteFileManager( m_wagonManager, settings, log );
        remoteFile.connect( repositoryId, url );

        // ======== LOCK REMOTE OBR ========
        log.info( "LOCK " + remoteFile + '/' + repositoryName );
        remoteFile.lockFile( repositoryName, ignoreLock );
        File downloadedRepositoryXml = null;

        try
        {
            // ======== DOWNLOAD REMOTE OBR ========
            log.info( "Downloading " + repositoryName );
            downloadedRepositoryXml = remoteFile.get( repositoryName, ".xml" );

            String mavenRepository = localRepository.getBasedir();

            URI repositoryXml = downloadedRepositoryXml.toURI();
            URI obrXmlFile = ObrUtils.toFileURI( obrXml );
            URI bundleJar;

            if ( null == file )
            {
                bundleJar = ObrUtils.findBundleJar( localRepository, project.getArtifact() );
            }
            else
            {
                bundleJar = file.toURI();
            }

            URI remoteBundleURI = null;
            if ( null != bundleUrl )
            {
                remoteBundleURI = URI.create( bundleUrl );
            }
            else if ( null != file )
            {
                remoteBundleURI = URI.create( localRepository.pathOf( project.getArtifact() ) );
            }

            Config userConfig = new Config();
            userConfig.setRemoteBundle( remoteBundleURI );
            userConfig.setPathRelative( true );
            userConfig.setRemoteFile( true );

            update = new ObrUpdate( repositoryXml, obrXmlFile, project, bundleJar, mavenRepository, userConfig, log );

            update.updateRepository();

            if ( downloadedRepositoryXml.exists() )
            {
                // ======== UPLOAD MODIFIED OBR ========
                log.info( "Uploading " + repositoryName );
                remoteFile.put( downloadedRepositoryXml, repositoryName );
            }
        }
        catch ( Exception e )
        {
            log.warn( "Exception while updating remote OBR: " + e.getLocalizedMessage(), e );
        }
        finally
        {
            // ======== UNLOCK REMOTE OBR ========
            log.info( "UNLOCK " + remoteFile + '/' + repositoryName );
            remoteFile.unlockFile( repositoryName );
            remoteFile.disconnect();

            if ( null != downloadedRepositoryXml )
            {
                downloadedRepositoryXml.delete();
            }
        }
    }
}
