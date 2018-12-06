/*
 * Copyright 2006-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openspaces.test.suspendtype.listeners;

import com.gigaspaces.cluster.activeelection.SpaceMode;
import com.gigaspaces.server.space.suspend.SuspendType;
import org.openspaces.core.space.suspend.SpaceChangeEvent;
import org.openspaces.core.space.suspend.SuspendTypeChangedListener;

/**
 * @author Elad GUr
 */
public class SpaceSuspendTypeChangedListenerByInterface extends AbstractSpaceSuspendTypeChangedListener
        implements SuspendTypeChangedListener {

    @Override
    public void onSuspendTypeChanged(SpaceChangeEvent event) {

        SpaceMode newSpaceMode = event.getSpaceMode();

        switch (newSpaceMode) {
            case NONE:
                // do when Space Mode changed to: NONE
                break;
            case BACKUP:
                // do when Space Mode changed to: BACKUP
                break;
            case PRIMARY:
                // do when Space Mode changed to: PRIMARY
                break;
        }

        SuspendType newSuspendType = event.getSuspendType();

        switch (newSuspendType) {
            case NONE:
                // do when suspend type changed to: NONE
                break;
            case QUIESCED:
                // do when suspend type changed to: QUIESCED
                break;
            case DEMOTING:
                // do when suspend type changed to: DEMOTING
                break;
            case DISCONNECTED:
                // do when suspend type changed to: DISCONNECTED
                break;
        }
    }

}