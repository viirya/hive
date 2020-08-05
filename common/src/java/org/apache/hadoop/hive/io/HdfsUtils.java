/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;

import org.apache.commons.lang.ArrayUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FsShell;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclEntryScope;
import org.apache.hadoop.fs.permission.AclEntryType;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hive.common.StorageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

public class HdfsUtils {

  private static final Logger LOG = LoggerFactory.getLogger("shims.HdfsUtils");

  // TODO: this relies on HDFS not changing the format; we assume if we could get inode ID, this
  //       is still going to work. Otherwise, file IDs can be turned off. Later, we should use
  //       as public utility method in HDFS to obtain the inode-based path.
  private static final String HDFS_ID_PATH_PREFIX = "/.reserved/.inodes/";

  public static Path getFileIdPath(
      FileSystem fileSystem, Path path, long fileId) {
    return (fileSystem instanceof DistributedFileSystem)
        ? new Path(HDFS_ID_PATH_PREFIX + fileId) : path;
  }

  /**
   * Copy the permissions, group, and ACLs from a source {@link HadoopFileStatus} to a target {@link Path}. This method
   * will only log a warning if permissions cannot be set, no exception will be thrown.
   *
   * @param conf the {@link Configuration} used when setting permissions and ACLs
   * @param sourceStatus the source {@link HadoopFileStatus} to copy permissions and ACLs from
   * @param fs the {@link FileSystem} that contains the target {@link Path}
   * @param target the {@link Path} to copy permissions, group, and ACLs to
   * @param recursion recursively set permissions and ACLs on the target {@link Path}
   */
  public static void setFullFileStatus(Configuration conf, HdfsUtils.HadoopFileStatus sourceStatus,
      FileSystem fs, Path target, boolean recursion) {
    if (StorageUtils.shouldSetPerms(conf, fs)) {
      setFullFileStatus(conf, sourceStatus, null, fs, target, recursion, true);
    }
  }

  /**
   * Copy the permissions, group, and ACLs from a source {@link HadoopFileStatus} to a target {@link Path}. This method
   * will only log a warning if permissions cannot be set, no exception will be thrown.
   *
   * @param conf the {@link Configuration} used when setting permissions and ACLs
   * @param sourceStatus the source {@link HadoopFileStatus} to copy permissions and ACLs from
   * @param targetGroup the group of the target {@link Path}, if this is set and it is equal to the source group, an
   *                    extra set group operation is avoided
   * @param fs the {@link FileSystem} that contains the target {@link Path}
   * @param target the {@link Path} to copy permissions, group, and ACLs to
   * @param recursion recursively set permissions and ACLs on the target {@link Path}
   */
  public static void setFullFileStatus(Configuration conf, HdfsUtils.HadoopFileStatus sourceStatus,
      String targetGroup, FileSystem fs, Path target, boolean recursion) {
    setFullFileStatus(conf, sourceStatus, targetGroup, fs, target, recursion, true);
  }

  public static void setFullFileStatus(Configuration conf, HdfsUtils.HadoopFileStatus sourceStatus,
      String targetGroup, FileSystem fs, Path target, boolean recursion, boolean isDir) {
    if (StorageUtils.shouldSetPerms(conf, fs)) {
      setFullFileStatus(conf, sourceStatus, targetGroup, fs, target, recursion, recursion ? new FsShell() : null, isDir);
    }
  }

  @VisibleForTesting
  static void setFullFileStatus(Configuration conf, HdfsUtils.HadoopFileStatus sourceStatus,
    String targetGroup, FileSystem fs, Path target, boolean recursion, FsShell fsShell) {
    setFullFileStatus(conf, sourceStatus, targetGroup, fs, target, recursion, fsShell, true);
  }

  @VisibleForTesting
  static void setFullFileStatus(Configuration conf, HdfsUtils.HadoopFileStatus sourceStatus,
    String targetGroup, FileSystem fs, Path target, boolean recursion, FsShell fsShell, boolean isDir) {
    try {
      FileStatus fStatus = sourceStatus.getFileStatus();
      String group = fStatus.getGroup();
      boolean aclEnabled = Objects.equal(conf.get("dfs.namenode.acls.enabled"), "true");
      FsPermission sourcePerm = fStatus.getPermission();
      List<AclEntry> aclEntries = null;
      if (aclEnabled) {
        if (sourceStatus.getAclEntries() != null && ! sourceStatus.getAclEntries().isEmpty()) {
          LOG.trace(sourceStatus.getAclStatus().toString());

          List<AclEntry> defaults = extractDefaultAcls(sourceStatus.getAclEntries());
          if (! defaults.isEmpty()) {
            // Generate child ACLs based on parent DEFAULTs.
            aclEntries = new ArrayList<AclEntry>(defaults.size() * 2);

            // All ACCESS ACLs are derived from the DEFAULT ACLs of the parent.
            // All DEFAULT ACLs of the parent are inherited by the child.
            // If DEFAULT ACLs exist, it should include DEFAULTs for USER, OTHER, and MASK.
            for (AclEntry acl : defaults) {
              // OTHER permissions are not inherited by the child.
              if (acl.getType() == AclEntryType.OTHER) {
                aclEntries.add(newAclEntry(AclEntryScope.ACCESS, AclEntryType.OTHER, FsAction.NONE));
              } else {
                aclEntries.add(newAclEntry(AclEntryScope.ACCESS, acl.getType(), acl.getName(), acl.getPermission()));
              }
            }

            // Add DEFAULTs for directories only; adding DEFAULTs for files throws an exception.
            if (isDir) {
              aclEntries.addAll(defaults);
            }
          } else {
            // Parent has no DEFAULTs, hence child inherits no ACLs.
            // Set basic permissions only.
            FsAction groupAction = null;

            for (AclEntry acl : sourceStatus.getAclEntries()) {
              if (acl.getType() == AclEntryType.GROUP) {
                groupAction = acl.getPermission();
                break;
              }
            }

            aclEntries = new ArrayList<AclEntry>(3);
            aclEntries.add(newAclEntry(AclEntryScope.ACCESS, AclEntryType.USER, sourcePerm.getUserAction()));
            aclEntries.add(newAclEntry(AclEntryScope.ACCESS, AclEntryType.GROUP, groupAction));
            aclEntries.add(newAclEntry(AclEntryScope.ACCESS, AclEntryType.OTHER, FsAction.NONE));
          }
        }
      }

      if (recursion) {
        //use FsShell to change group, permissions, and extended ACL's recursively
        fsShell.setConf(conf);
        //If there is no group of a file, no need to call chgrp
        if (group != null && !group.isEmpty()) {
          run(fsShell, new String[]{"-chgrp", "-R", group, target.toString()});
        }

        if (aclEntries != null) {
          try {
            //construct the -setfacl command
            String aclEntry = Joiner.on(",").join(aclEntries);
            run(fsShell, new String[]{"-setfacl", "-R", "--set", aclEntry, target.toString()});
          } catch (Exception e) {
            LOG.info("Skipping ACL inheritance: File system for path " + target + " " +
                "does not support ACLs but dfs.namenode.acls.enabled is set to true. ");
            LOG.debug("The details are: " + e, e);
          }
        } else {
          String permission = Integer.toString(sourcePerm.toShort(), 8);
          run(fsShell, new String[]{"-chmod", "-R", permission, target.toString()});
        }
      } else {
        if (group != null && !group.isEmpty()) {
          if (targetGroup == null || !group.equals(targetGroup)) {
            fs.setOwner(target, null, group);
          }
        }

        if (aclEntries != null) {
          fs.setAcl(target, aclEntries);
        } else {
          fs.setPermission(target, sourcePerm);
        }
      }
    } catch (Exception e) {
      LOG.warn(
              "Unable to inherit permissions for file " + target + " from file " + sourceStatus.getFileStatus().getPath(),
              e.getMessage());
      LOG.debug("Exception while inheriting permissions", e);
    }
  }

  /**
   * Create a new AclEntry with scope, type and permission (no name).
   * @param scope AclEntryScope scope of the ACL entry
   * @param type AclEntryType ACL entry type
   * @param permission FsAction set of permissions in the ACL entry
   * @return AclEntry new AclEntry
   */
  private static AclEntry newAclEntry(AclEntryScope scope, AclEntryType type,
      FsAction permission) {
    return newAclEntry(scope, type, null, permission);
  }

  /**
   * Create a new AclEntry with scope, type and permission (no name).
   * @param scope AclEntryScope scope of the ACL entry
   * @param type AclEntryType ACL entry type
   * @param name AclEntry name
   * @param permission FsAction set of permissions in the ACL entry
   * @return AclEntry new AclEntry
   */
  private static AclEntry newAclEntry(AclEntryScope scope, AclEntryType type,
      String name, FsAction permission) {
    return new AclEntry.Builder().setScope(scope).setType(type).setName(name)
        .setPermission(permission).build();
  }

  /**
   * Extracts the DEFAULT ACL entries from the list of acl entries
   * @param acls acl entries to extract from
   * @return default unnamed acl entries
   */
  private static List<AclEntry> extractDefaultAcls(List<AclEntry> acls) {
    List<AclEntry> defaults = new ArrayList<AclEntry>(acls);
    Iterables.removeIf(defaults, new Predicate<AclEntry>() {
      @Override
      public boolean test(AclEntry acl) {
        if (! acl.getScope().equals(AclEntryScope.DEFAULT)) {
          return true;
        }
        return false;
      }

      @Override
      public boolean apply(AclEntry acl) {
        if (! acl.getScope().equals(AclEntryScope.DEFAULT)) {
          return true;
        }
        return false;
      }
    });

    return defaults;
  }

  private static void run(FsShell shell, String[] command) throws Exception {
    LOG.debug(ArrayUtils.toString(command));
    int retval = shell.run(command);
    LOG.debug("Return value is :" + retval);
  }

  public static class HadoopFileStatus {

    private final FileStatus fileStatus;
    private final AclStatus aclStatus;

    public HadoopFileStatus(Configuration conf, FileSystem fs, Path file) throws IOException {

      FileStatus fileStatus = fs.getFileStatus(file);
      AclStatus aclStatus = null;
      if (Objects.equal(conf.get("dfs.namenode.acls.enabled"), "true")) {
        //Attempt extended Acl operations only if its enabled, but don't fail the operation regardless.
        try {
          aclStatus = fs.getAclStatus(file);
        } catch (Exception e) {
          LOG.info("Skipping ACL inheritance: File system for path " + file + " " +
                  "does not support ACLs but dfs.namenode.acls.enabled is set to true. ");
          LOG.debug("The details are: " + e, e);
        }
      }
      this.fileStatus = fileStatus;
      this.aclStatus = aclStatus;
    }

    public FileStatus getFileStatus() {
      return fileStatus;
    }

    public List<AclEntry> getAclEntries() {
      return aclStatus == null ? null : Collections.unmodifiableList(aclStatus.getEntries());
    }

    @VisibleForTesting
    AclStatus getAclStatus() {
      return this.aclStatus;
    }
  }

  public static void setParentFileStatus(
      Configuration conf, FileSystem fs, Path destPath, boolean recursive) throws IOException {
    setFullFileStatus(conf, new HdfsUtils.HadoopFileStatus(conf, fs, destPath.getParent()),
        fs, destPath, recursive);
  }
}
