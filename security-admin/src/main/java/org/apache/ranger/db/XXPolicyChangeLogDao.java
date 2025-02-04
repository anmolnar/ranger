/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.db;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.common.db.BaseDao;
import org.apache.ranger.entity.XXPolicy;
import org.apache.ranger.entity.XXPolicyChangeLog;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicyDelta;
import org.apache.ranger.service.RangerPolicyService;
import org.springframework.stereotype.Service;

/**
 */
@Service
public class XXPolicyChangeLogDao extends BaseDao<XXPolicyChangeLog> {

    private static final Log LOG = LogFactory.getLog(XXPolicyChangeLogDao.class);

    /**
     * Default Constructor
     */
    public XXPolicyChangeLogDao(RangerDaoManagerBase daoManager) {
        super(daoManager);
    }

    public List<RangerPolicyDelta> findLaterThan(RangerPolicyService policyService, Long version, Long serviceId) {
        final List<RangerPolicyDelta> ret;
        if (version != null) {
            List<Object[]> logs = getEntityManager()
                    .createNamedQuery("XXPolicyChangeLog.findSinceVersion", Object[].class)
                    .setParameter("version", version)
                    .setParameter("serviceId", serviceId)
                    .getResultList();

            // Ensure that some record has the same version as the base-version from where the records are fetched
            if (CollectionUtils.isNotEmpty(logs)) {
                Iterator<Object[]> iter = logs.iterator();
                boolean foundAndRemoved = false;

                while (iter.hasNext()) {
                    Object[] record = iter.next();
                    Long recordVersion = (Long) record[2];
                    if (version.equals(recordVersion)) {
                        iter.remove();
                        foundAndRemoved = true;
                    } else {
                        break;
                    }
                }
                if (foundAndRemoved) {
                    ret = convert(policyService, logs);
                } else {
                    ret = null;
                }
            } else {
                ret = null;
            }
        } else {
            ret = null;
        }
        return ret;
    }

    public List<RangerPolicyDelta> findGreaterThan(RangerPolicyService policyService, Long id, Long serviceId) {
        final List<RangerPolicyDelta> ret;
        if (id != null) {
            List<Object[]> logs = getEntityManager()
                    .createNamedQuery("XXPolicyChangeLog.findGreaterThan", Object[].class)
                    .setParameter("id", id)
                    .setParameter("serviceId", serviceId)
                    .getResultList();
            ret = convert(policyService, logs);
        } else {
            ret = null;
        }
        return ret;
    }

    public void deleteOlderThan(int olderThanInDays) {

        Date since = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(olderThanInDays));

        if (LOG.isDebugEnabled()) {
            LOG.debug("Deleting records from x_policy_change_log that are older than " + olderThanInDays + " days, that is,  older than " + since);
        }

        getEntityManager().createNamedQuery("XXPolicyChangeLog.deleteOlderThan").setParameter("olderThan", since).executeUpdate();
    }

    private List<RangerPolicyDelta> convert(RangerPolicyService policyService, List<Object[]> queryResult) {

        final List<RangerPolicyDelta> ret;

        if (CollectionUtils.isNotEmpty(queryResult)) {

            ret = new ArrayList<>(queryResult.size());

            for (Object[] log : queryResult) {

                RangerPolicy policy;

                Long    logRecordId      = (Long) log[0];
                Integer policyChangeType = (Integer) log[1];
                String  serviceType      = (String) log[3];
                Long    policyId         = (Long) log[5];

                if (policyId != null) {
                    XXPolicy xxPolicy = daoManager.getXXPolicy().getById(policyId);
                    if (xxPolicy != null) {
                        try {
                            policy = policyService.read(policyId);
                        } catch (Exception e) {
                            LOG.error("Cannot read policy:[" + policyId + "]. Should not have come here!! Offending log-record-id:[" + logRecordId + "] and returning...", e);
                            ret.clear();
                            ret.add(new RangerPolicyDelta(logRecordId, RangerPolicyDelta.CHANGE_TYPE_LOG_ERROR, null));
                            break;
                        }
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Policy:[" + policyId + "] not found - log-record - id:[" + logRecordId + "], PolicyChangeType:[" + policyChangeType + "]");
                        }

                        // Create a dummy policy as the policy cannot be found - probably already deleted
                        policy = new RangerPolicy();
                        policy.setId(policyId);
                        policy.setVersion((Long) log[2]);
                        policy.setPolicyType((Integer) log[4]);
                        policy.setZoneName((String) log[6]);
                    }
                    policy.setServiceType(serviceType);

                    ret.add(new RangerPolicyDelta(logRecordId, policyChangeType, policy));
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("policyId is null! log-record-id:[" + logRecordId + ", service-type:[" + log[3] + "], policy-change-type:[" + log[1] + "]");
                    }
                    ret.clear();
                    ret.add(new RangerPolicyDelta(logRecordId, policyChangeType, null));
                    break;
                }
            }
        } else {
            ret = null;
        }
        return ret;

    }

}
