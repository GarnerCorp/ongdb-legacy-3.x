/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.ha.upgrade;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.neo4j.io.fs.FileUtils;

import static java.lang.Runtime.getRuntime;
import static java.util.Arrays.asList;
import static org.apache.commons.io.FileUtils.copyURLToFile;
import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.io.IOUtils.copy;
import static org.neo4j.io.fs.FileUtils.deleteFile;
import static org.neo4j.io.fs.FileUtils.deleteRecursively;
import static org.neo4j.io.fs.FileUtils.moveFile;

public class Utils
{
    private Utils()
    {
    }

    public static String assembleClassPathFromPackage( File directory )
    {
        List<File> jarFiles = new ArrayList<>();
        FileFilter jarFilter = file -> file.isFile() && file.getName().endsWith( ".jar" );
        gatherFiles( jarFiles, directory, jarFilter );

        StringBuilder classpath = new StringBuilder();
        for ( File file : jarFiles )
        {
            classpath.append( classpath.length() > 0 ? File.pathSeparator : "" ).append( file.getAbsolutePath() );
        }
        return classpath.toString();
    }

    private static void gatherFiles( List<File> jarFiles, File directory, FileFilter filter )
    {
        for ( File file : directory.listFiles() )
        {
            if ( file.isDirectory() )
            {
                gatherFiles( jarFiles, file, filter );
            }
            else if ( filter.accept( file ) )
            {
                jarFiles.add( file );
            }
        }
    }

    public static File downloadAndUnpack( String url, File targetDirectory, String downloadedFileName ) throws IOException
    {
        URL website = new URL( url );
        File downloaded = new File( targetDirectory, downloadedFileName + ".zip" );
        if ( !downloaded.exists() )
        {
            File tmpDownload = new File( downloaded.getAbsolutePath() + ".tmp" );
            deleteFile( tmpDownload );
            copyURLToFile( website, tmpDownload, 5000, 10000 );
            moveFile( tmpDownload, downloaded );
        }

        File unpacked = new File( targetDirectory, downloadedFileName );
        if ( !unpacked.exists() )
        {
            File tmpUnpack = new File( unpacked.getAbsolutePath() + "-tmp" );
            deleteRecursively( tmpUnpack );
            tmpUnpack.mkdirs();
            unzip( downloaded, tmpUnpack );
            FileUtils.moveFile( tmpUnpack, unpacked );
        }
        return unpacked;
    }

    public static List<File> unzip( File zipFile, File targetDir ) throws IOException
    {
        List<File> files = new ArrayList<>();
        ZipFile zip = new ZipFile( zipFile );
        try
        {
            zip = new ZipFile( zipFile );
            for ( ZipEntry entry : Collections.list( zip.entries() ) )
            {
                File target = new File( targetDir, entry.getName() );
                target.getParentFile().mkdirs();
                if ( !entry.isDirectory() )
                {
                    InputStream input = zip.getInputStream( entry );
                    try
                    {
                        copyInputStreamToFile( input, target );
                        files.add( target );
                    }
                    finally
                    {
                        closeQuietly( input );
                    }
                }
            }
            return files;
        }
        finally
        {
            zip.close();
        }
    }

    public static void copyInputStreamToFile( InputStream stream, File target ) throws IOException
    {
        OutputStream out = null;
        try
        {
            out = new FileOutputStream( target );
            copy( stream, out );
        }
        finally
        {
            closeQuietly( out );
        }
    }

    public static Process execJava( String classPath, String mainClass, String... args ) throws Exception
    {
        List<String> allArgs = new ArrayList<>( asList( "java", "-cp", classPath, mainClass ) );
        allArgs.addAll( asList( args ) );
        return getRuntime().exec( allArgs.toArray( new String[0] ) );
    }

}
