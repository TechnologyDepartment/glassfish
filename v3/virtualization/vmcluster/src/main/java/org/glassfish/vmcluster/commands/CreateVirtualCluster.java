/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2011 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.vmcluster.commands;

/**
 * Creates a virtual cluster
 */

import org.glassfish.api.ActionReport;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.vmcluster.config.Template;
import org.glassfish.vmcluster.config.Virtualization;
import org.glassfish.vmcluster.config.Virtualizations;
import org.glassfish.vmcluster.runtime.VirtualCluster;
import org.glassfish.vmcluster.runtime.VirtualClusters;
import org.glassfish.vmcluster.spi.VirtException;
import org.glassfish.vmcluster.spi.VirtualMachine;
import org.glassfish.vmcluster.util.RuntimeContext;
import org.glassfish.vmcluster.virtmgt.GroupAccess;
import org.glassfish.vmcluster.virtmgt.GroupsAccess;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.PerLookup;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.Future;
import java.util.logging.Level;
import org.glassfish.api.admin.CommandLock;

@Service(name="create-virtual-cluster")
@Scoped(PerLookup.class)
@CommandLock(CommandLock.LockType.NONE)
public class CreateVirtualCluster implements AdminCommand {

    @Param(optional = true)
    String groupNames=null;

    @Param(optional=true)
    String template=null;

    @Param(primary = true)
    String name;

    @Param(optional = true, defaultValue = "1")
    String min = "1";

    @Param(optional = true, defaultValue = "5")
    String max = "5";

    @Inject
    GroupsAccess groups;

    @Inject
    Virtualizations virts;

    @Inject
    VirtualClusters clusters;

    @Inject
    RuntimeContext rtContext;

    @Override
    public void execute(AdminCommandContext context) {


        List<GroupAccess> targetGroups = new ArrayList<GroupAccess>();
        if (groupNames==null) {
            for (GroupAccess group : groups.groups()) {
                targetGroups.add(group);
            }
        } else {
            StringTokenizer tokenizer = new StringTokenizer(groupNames, ",");
            while (tokenizer.hasMoreElements()) {
                String groupName = tokenizer.nextToken();
                GroupAccess group = groups.byName(groupName);
                if (group==null) {
                    context.getActionReport().failure(RuntimeContext.logger, "There are not defined groups named " + groupName);
                    return;
                }
                targetGroups.add(group);
            }
        }
        if (targetGroups.isEmpty()) {
            context.getActionReport().failure(RuntimeContext.logger, "There are not defined groups to deploy to");
            return;
        }

        ActionReport report = context.getActionReport();
        StringBuilder sb = new StringBuilder();
        int minNumber = Integer.parseInt(min);
        sb.append("Successfully created ").append(minNumber).append(" virtual machine(s) : ");

        // so far, we are pretty simple, each group gets to allocate the same number of virtual machines.
        int numberPerGroup = minNumber / targetGroups.size();

        for (GroupAccess targetGroup : targetGroups) {
            Virtualization virt = virts.byName(targetGroup.getVirtualizationName());
            if (virt==null) {
                context.getActionReport().failure(RuntimeContext.logger, "Cannot find virtualization configuration " +
                        targetGroup.getVirtualizationName());
                return;
            }
            Template template_;
            if (template!=null) {
                template_ = virt.templateByName(template);
            } else {
                template_ = virt.getTemplates().get(0);
            }
            List<VirtualMachine> vms = new ArrayList<VirtualMachine>();
            rtContext.executeAdminCommand(report, "create-cluster", name);
            if (report.hasFailures()) {
                return;
            }
            final VirtualCluster vc =  clusters.byName(name);

            try {
                Iterable<Future<VirtualMachine>> futures = targetGroup.allocate(template_, vc, numberPerGroup);
                for (Future<VirtualMachine> future : futures) {
                    VirtualMachine vm;
                    try {
                        vm = future.get();
                    } catch(Exception e) {
                        context.getActionReport().failure(RuntimeContext.logger, "Failure to allocate virtual machine ", e);
                        return;
                    }
                    vms.add(vm);
                    sb.append(vm.getName()).append( "(").append(vm.getAddress()).append(") ");
                }
            } catch(VirtException e) {
                rtContext.executeAdminCommand(report, "delete-cluster", name);
                RuntimeContext.logger.log(Level.SEVERE, e.getMessage(), e);
                context.getActionReport().failure(RuntimeContext.logger, e.getMessage());
                return;
            }
        }
        report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
        report.setMessage(sb.toString());
    }
}
