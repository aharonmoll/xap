package com.gigaspaces.internal.server.space.demote;

import com.gigaspaces.admin.quiesce.DefaultQuiesceToken;
import com.gigaspaces.internal.server.space.SpaceImpl;
import com.gigaspaces.logger.Constants;
import com.j_spaces.core.filters.ReplicationStatistics;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.j_spaces.core.Constants.LeaderSelector.LEADER_SELECTOR_HANDLER_CLASS_NAME;


/**
 * @author Yohana Khoury
 * @since 14.0
 */
@com.gigaspaces.api.InternalApi
public class DemoteHandler {
    private final Logger _logger;
    private final SpaceImpl _spaceImpl;
    private volatile boolean isDemoteInProgress = false;

    public DemoteHandler(SpaceImpl spaceImpl) {
        _spaceImpl = spaceImpl;
        _logger = Logger.getLogger(Constants.LOGGER_DEMOTE+ '.' + spaceImpl.getNodeName());

    }

    public void demote(int timeToWait, TimeUnit unit) throws DemoteException {
//        _spaceImpl.beforeOperation(true, false /*checkQuiesceMode*/, null);
//        _spaceImpl.getQuiesceHandler().suspend("nnnn");

        isDemoteInProgress = true;
        demoteImpl(timeToWait,unit);
        isDemoteInProgress = false;
    }

    public boolean demoteImpl(int timeToWait, TimeUnit unit) throws DemoteException {
        long timeToWaitInMs = unit.toMillis(timeToWait);
        long end = timeToWaitInMs + System.currentTimeMillis();

        if (!_spaceImpl.isPrimary()) {
            //space is not primary
            return false;
        }

        validateCanProgress();

        if (!_spaceImpl.useZooKeeper()) {
            _logger.info("Primary demotion is only supported with Zookeeper leader selector.");
            return false;
        }

        if (_spaceImpl.getClusterInfo().getNumberOfBackups() != 1) {
            _logger.info("Couldn't demote to backup - cluster should be configured with exactly one backup, backups: (" + _spaceImpl.getClusterInfo().getNumberOfBackups() + ")");
            return false;
        }

        //In case that we use ZooKeeper but leader selector is not ZK based leader selector
        if (_spaceImpl.getLeaderSelector() == null || !_spaceImpl.getLeaderSelector().getClass().getName().equals(LEADER_SELECTOR_HANDLER_CLASS_NAME)) {
            _logger.info("Primary demotion is only supported with Zookeeper leader selector.");
            return false;
        }

        List<ReplicationStatistics.OutgoingChannel> backupChannels = _spaceImpl.getHolder().getReplicationStatistics().getOutgoingReplication().getChannels(ReplicationStatistics.ReplicationMode.BACKUP_SPACE);
        if (backupChannels.size() != 1) {
            //more than one backup
            _logger.info("There should be exactly one backup, current channels: ("+backupChannels.size()+")");
            return false;
        }

        ReplicationStatistics.OutgoingChannel backupChannel = backupChannels.get(0);

        if (!backupChannel.getChannelState().equals(ReplicationStatistics.ChannelState.ACTIVE)) {
            //backup replication is not active
            _logger.info("Backup replication channel is not active ("+backupChannel.getChannelState()+")");
            return false;
        }


        try {

            //TODO quiesce token - replace with empty token
            _logger.info("Demoting to backup, entering quiesce mode...");
            _spaceImpl.getQuiesceHandler().quiesce("Space is demoting from primary to backup", new DefaultQuiesceToken("myToken"));


            long remainingTime = end - System.currentTimeMillis();
            boolean leaseManagerCycleFinished = _spaceImpl.getEngine().getLeaseManager().waitForNoCycleOnQuiesce(remainingTime);
            if (!leaseManagerCycleFinished) {
                _logger.info("Couldn't demote to backup - lease manager cycle timeout");
                return false;
            }

            remainingTime = end - System.currentTimeMillis();

            if (remainingTime <= 0) {
                _logger.info("Couldn't demote to backup - timeout waiting for a lease manager cycle");
                return false;
            }

//            remainingTime = end - System.currentTimeMillis();
//            _spaceImpl.getEngine().getTransactionHandler().abortOpenTransactions();
//            _spaceImpl.getEngine().getTransactionHandler().waitForActiveTransactions(remainingTime);

            remainingTime = end - System.currentTimeMillis();
            if (remainingTime <= 0) {
                _logger.info("Couldn't demote to backup - timeout while waiting for active transactions");
                return false;
            }


            //Sleep for the remaining time
            try {
                Thread.sleep(remainingTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }


            long lastKeyInRedoLog = _spaceImpl.getHolder().getReplicationStatistics().getOutgoingReplication().getLastKeyInRedoLog();

            if (_spaceImpl.getHolder().getReplicationStatistics().getOutgoingReplication().getChannels(ReplicationStatistics.ReplicationMode.BACKUP_SPACE).get(0).getLastConfirmedKeyFromTarget() != lastKeyInRedoLog) {
                //Backup is not synced
                _logger.info("Couldn't demote to backup - backup is not synced ("+backupChannel.getLastConfirmedKeyFromTarget()+" != "+lastKeyInRedoLog+")");
                return false;
            }

            _logger.info("size during demote = "+_spaceImpl.getHolder().getReplicationStatistics().getOutgoingReplication().getRedoLogSize());
            //TODO recheck above preconditions again after entering quiesce mode
            //Wait timeout if necessary (e.g. wait for replication from primary to backup)

            // wait for no activity
            //TODO
            validateCanProgress();
            //close outgoing connections
            closeChannels();

            //Verify that there was no issues against ZK like suspend, disconnect
            // If already suspended, abort
            validateCanProgress();

            //Restart leader selector handler (in case of ZK then it restarts
            // the connection to ZK so the backup becomes primary)
            //If suspend happens, we need to clean the suspend after the demote!
            if (!_spaceImpl.restartLeaderSelectorHandler()) {
                throw new DemoteException("Could not restart leader selector");
            }

        } catch (DemoteException e) {
            abort();
            throw e;
        } finally {
            _logger.info("Demoting to backup finished, exiting quiesce mode...");
            _spaceImpl.getQuiesceHandler().unquiesce();
        }


        return true;
    }

    public boolean isDemoteInProgress() {
        return isDemoteInProgress;
    }

    private boolean isSuspended() {
        return _spaceImpl.getQuiesceHandler().isSuspended();
    }


    private void closeChannels() {
        _spaceImpl.getEngine().getReplicationNode().getAdmin().setPassive(false);
    }

    private void validateCanProgress() throws DemoteException {
        if (isSuspended()) {
            throw new DemoteException(ERR_SPACE_IS_SUSPENDED);
        }
        if (isQuiesced()) {
            throw new DemoteException(ERR_SPACE_IS_QUIESCED);
        }
    }

    private boolean isQuiesced() {
        return _spaceImpl.getQuiesceHandler().isQuiesced();
    }


    private void abort() {
        _spaceImpl.getEngine().getReplicationNode().getAdmin().setActive();

    }

  public final String ERR_SPACE_IS_SUSPENDED = "Space is suspended";
  public final String ERR_SPACE_IS_QUIESCED = "Space is quiesced";

}