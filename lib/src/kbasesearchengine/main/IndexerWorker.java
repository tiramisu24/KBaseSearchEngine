package kbasesearchengine.main;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonParser;
import com.google.common.base.Optional;

import kbasesearchengine.common.FileUtil;
import kbasesearchengine.common.GUID;
import kbasesearchengine.common.GUIDTooLongException;
import kbasesearchengine.events.ChildStatusEvent;
import kbasesearchengine.events.StatusEvent;
import kbasesearchengine.events.StatusEventProcessingState;
import kbasesearchengine.events.StatusEventWithId;
import kbasesearchengine.events.StoredStatusEvent;
import kbasesearchengine.events.exceptions.ErrorType;
import kbasesearchengine.events.exceptions.FatalIndexingException;
import kbasesearchengine.events.exceptions.FatalRetriableIndexingException;
import kbasesearchengine.events.exceptions.IndexingException;
import kbasesearchengine.events.exceptions.IndexingExceptionUncheckedWrapper;
import kbasesearchengine.events.exceptions.RetriableIndexingException;
import kbasesearchengine.events.exceptions.RetriableIndexingExceptionUncheckedWrapper;
import kbasesearchengine.events.exceptions.Retrier;
import kbasesearchengine.events.exceptions.UnprocessableEventIndexingException;
import kbasesearchengine.events.handler.EventHandler;
import kbasesearchengine.events.handler.ResolvedReference;
import kbasesearchengine.events.handler.SourceData;
import kbasesearchengine.events.storage.StatusEventStorage;
import kbasesearchengine.parse.ContigLocationException;
import kbasesearchengine.parse.GUIDNotFoundException;
import kbasesearchengine.parse.KeywordParser;
import kbasesearchengine.parse.ObjectParseException;
import kbasesearchengine.parse.ObjectParser;
import kbasesearchengine.parse.ParsedObject;
import kbasesearchengine.parse.KeywordParser.ObjectLookupProvider;
import kbasesearchengine.search.IndexingConflictException;
import kbasesearchengine.search.IndexingStorage;
import kbasesearchengine.search.ObjectData;
import kbasesearchengine.system.NoSuchTypeException;
import kbasesearchengine.system.ObjectTypeParsingRules;
import kbasesearchengine.system.ParsingRulesSubtypeFirstComparator;
import kbasesearchengine.system.SearchObjectType;
import kbasesearchengine.system.StorageObjectType;
import kbasesearchengine.system.TypeStorage;
import org.apache.commons.io.FileUtils;

public class IndexerWorker implements Stoppable {
    
    //TODO JAVADOC
    //TODO TESTS
    
    private final String id;
    private final File rootTempDir;
    private final StatusEventStorage storage;
    private final TypeStorage typeStorage;
    private final IndexingStorage indexingStorage;
    private final Set<String> workerCodes;
    private final LineLogger logger;
    private final Map<String, EventHandler> eventHandlers;
    private ScheduledExecutorService executor = null;
    private final SignalMonitor signalMonitor = new SignalMonitor();
    private boolean stopRunner = false;
    private final int maxObjectsPerLoad;
    private final Retrier retrier;

    public IndexerWorker(final IndexerWorkerConfigurator config) throws IOException {
        this.maxObjectsPerLoad = config.getMaxObjectsPerLoad();
        this.workerCodes = config.getWorkerCodes();
        this.logger = config.getLogger();
        this.logger.logInfo("Worker codes: " + workerCodes);
        this.id = config.getWorkerID();
        this.rootTempDir = FileUtil.getOrCreateCleanSubDir(config.getRootTempDir().toFile(),
                id + "_" + UUID.randomUUID().toString().substring(0,5));
        this.logger.logInfo("Created temp dir " + rootTempDir.getAbsolutePath() +
                                                     " for indexer worker " + id);
        
        this.eventHandlers = config.getEventHandlers();
        this.storage = config.getEventStorage();
        this.typeStorage = config.getTypeStorage();
        this.indexingStorage = config.getIndexingStorage();
        this.retrier = new Retrier(config.getRetryCount(), config.getRetrySleepMS(),
                config.getRetryFatalBackoffMS(),
                (retrycount, event, except) -> logError(retrycount, event, except));
    }
    
    @Override
    public void awaitShutdown() throws InterruptedException {
        signalMonitor.awaitSignal();
    }
    
    public void startIndexer() {
        stopRunner = false;
        //TODO TEST add a way to inject an executor for testing purposes
        executor = Executors.newSingleThreadScheduledExecutor();
        // may want to make this configurable
        executor.scheduleAtFixedRate(new IndexerRunner(), 0, 1000, TimeUnit.MILLISECONDS);
    }
    
    private class IndexerRunner implements Runnable {

        @Override
        public void run() {
            boolean processedEvent = true;
            while (!stopRunner && processedEvent) {
                processedEvent = false;
                try {
                    // keep processing events until there are none left
                    processedEvent = runCycle();
                } catch (InterruptedException | FatalIndexingException e) {
                    logError(LogPrefix.FATAL, e);
                    executor.shutdown();
                    signalMonitor.signal();
                } catch (Throwable e) {
                    logError(LogPrefix.UNEXPECTED, e);
                }
            }
        }
    }
    
    @Override
    public void stop(long millisToWait) throws InterruptedException {
        if (millisToWait < 0) {
            millisToWait = 0;
        }
        stopRunner = true;
        executor.shutdown();
        executor.awaitTermination(millisToWait, TimeUnit.MILLISECONDS);

        try {
            FileUtils.deleteDirectory(rootTempDir);
        } catch(IOException ioe) {
            logger.logError("Unable to delete worker temp dir "+rootTempDir+
                    " on shutdown of worker with id "+id+
                    ". Please delete manually. "+ioe.getMessage());
        }
    }
    
    private enum LogPrefix {
        STD, FATAL, UNEXPECTED;
    }
    
    private void logError(final LogPrefix prefix, final Throwable e) {
        final String logPrefix;
        if (LogPrefix.FATAL.equals(prefix)) {
            logPrefix = "Fatal error in indexer, shutting down";
        } else if (LogPrefix.STD.equals(prefix)) {
            logPrefix = "Error in indexer";
        } else if (LogPrefix.UNEXPECTED.equals(prefix)) {
            logPrefix = "Unexpected error in indexer";
        } else {
            throw new RuntimeException("Unknown error type: " + prefix);
        }
        logError(logPrefix, e);
    }

    private void logError(final String logPrefix, final Throwable e) {
        // TODO LOG make log method that takes msg + e and have the logger figure out how to log it correctly
        logger.logError(logPrefix + ": " + e);
        logger.logError(e);
    }

    private void logError(
            final int retrycount,
            final Optional<StatusEventWithId> event,
            final RetriableIndexingException e) {
        String prefix = "Retriable";
        if (e instanceof FatalRetriableIndexingException) {
            // add isFatal() method or something if the exception hierarchy gets much bigger
            prefix = "Fatal retriable";
        }
        final String msg;
        if (event.isPresent()) {
            msg = String.format("%s error in indexer for event %s %s%s, retry %s",
                    prefix,
                    event.get().getEvent().getEventType(),
                    event.get().isParentId() ? "with parent ID " : "",
                    event.get().getID().getId(),
                    retrycount);
        } else {
            msg = String.format("%s error in indexer, retry %s", prefix, retrycount);
        }
        logError(msg, e);
    }
    
    /** Runs one cycle of the event processing loop, processing up to one event.
     * @return true if an event was processed, false if not.
     * @throws InterruptedException if the thread was interrupted.
     * @throws FatalIndexingException if an indexing exception occurred that should cause the
     * shutdown of the worker. In normal use, no more events will be processed.
     */
    public boolean runCycle() throws InterruptedException, FatalIndexingException {
        final Optional<StoredStatusEvent> optEvent;
        try {
            optEvent = retrier.retryFunc(
                    s -> s.setAndGetProcessingState(StatusEventProcessingState.READY, workerCodes,
                            StatusEventProcessingState.PROC, id),
                    storage, null);
        } catch (FatalIndexingException e) {
            throw e;
        } catch (IndexingException e) { // untestable
            throw new RuntimeException("non-fatal exceptions should not be thrown here");
        }
        boolean processedEvent = false;
        if (optEvent.isPresent()) {
            final StoredStatusEvent parentEvent = optEvent.get();
            final EventHandler handler;
            try {
                handler = getEventHandler(parentEvent);
            } catch (UnprocessableEventIndexingException e) {
                handleException("Error getting event handler", parentEvent, e);
                return true;
            }
            if (handler.isExpandable(parentEvent)) {
                expandAndProcess(parentEvent);
            } else {
                // this means failed events get marked twice, since processEvent marks failed
                // events
                // *shrug*
                // maybe rethink this whole process later, but now would require interface changes
                markEventProcessed(parentEvent, processEvent(parentEvent));
            }
            processedEvent = true;
        }
        return processedEvent;
    }

    private void markEventProcessed(
            final StoredStatusEvent parentEvent,
            final StatusEventProcessingState result)
            throws InterruptedException, FatalIndexingException {
        try {
            // should only throw fatal
            retrier.retryCons(s -> s.setProcessingState(parentEvent.getID(),
                    StatusEventProcessingState.PROC, result), storage, parentEvent);
        } catch (FatalIndexingException | InterruptedException e) {
            throw e;
        } catch (IndexingException e) { // untestable
            throw new RuntimeException("non-fatal exceptions should not be thrown here", e);
        }
    }

    private void expandAndProcess(final StoredStatusEvent parentEvent)
            throws FatalIndexingException, InterruptedException {
        logger.logInfo(String.format("[Indexer] Expanding event %s %s",
                parentEvent.getEvent().getEventType(), parentEvent.getID().getId()));
        final Iterator<ChildStatusEvent> childIter;
        try {
            childIter = retrier.retryFunc(e -> getSubEventIterator(e), parentEvent, parentEvent);
        } catch (IndexingException e) {
            handleException("Error expanding parent event", parentEvent, e);
            return;
        } catch (InterruptedException e) {
            throw e;
        }
        StatusEventProcessingState parentResult = StatusEventProcessingState.INDX;
        while (childIter.hasNext()) {
            ChildStatusEvent subev = null;
            try {
                subev = retrier.retryFunc(i -> getNextSubEvent(i), childIter, parentEvent);
            } catch (IndexingException e) {
                handleException("Error getting event information from data storage",
                        parentEvent, e);
                parentResult = StatusEventProcessingState.FAIL;
            }
            if (subev != null && StatusEventProcessingState.FAIL.equals(processEvent(subev))) {
                parentResult = StatusEventProcessingState.FAIL;
            }
        }
        markEventProcessed(parentEvent, parentResult);
    }
    
    private Iterator<ChildStatusEvent> getSubEventIterator(final StoredStatusEvent ev)
            throws IndexingException, RetriableIndexingException {
        try {
            return getEventHandler(ev).expand(ev).iterator();
        } catch (IndexingExceptionUncheckedWrapper e) {
            throw e.getIndexingException();
        } catch (RetriableIndexingExceptionUncheckedWrapper e) {
            throw e.getIndexingException();
        }
    }

    /** Process an event.
     * Events which call for indexing new data for which a set of parsing rules is not present
     * are skipped.
     * Attempting to process expandable events via this method will result in a
     * {@link StatusEventProcessingState#FAIL}.
     * @param ev the event to process.
     * @return the state of the completed event.
     * @throws InterruptedException if the thread is interrupted.
     * @throws FatalIndexingException if an indexing exception occurs that is unrecoverable.
     */
    public StatusEventProcessingState processEvent(final StatusEventWithId ev)
            throws InterruptedException, FatalIndexingException {
        final Optional<StorageObjectType> type = ev.getEvent().getStorageObjectType();
        if (type.isPresent() && !isStorageTypeSupported(type.get())) {
            logger.logInfo("[Indexer] skipping " + ev.getEvent().getEventType() + ", " + 
                    toLogString(type) + ev.getEvent().toGUID());
            return StatusEventProcessingState.UNINDX;
        }
        logger.logInfo("[Indexer] processing " + ev.getEvent().getEventType() + ", " + 
                toLogString(type) + ev.getEvent().toGUID() + "...");
        final long time = System.currentTimeMillis();
        try {
            retrier.retryCons(e -> processEvent(e), ev.getEvent(), ev);
        } catch (IndexingException e) {
            handleException("Error processing event", ev, e);
            return StatusEventProcessingState.FAIL;
        }
        logger.logInfo("[Indexer]   (total time: " + (System.currentTimeMillis() - time) + "ms.)");
        return StatusEventProcessingState.INDX;
    }
    
    private boolean isStorageTypeSupported(final StorageObjectType storageObjectType) {
        return !typeStorage.listObjectTypeParsingRules(storageObjectType).isEmpty();
    }
    
    private ChildStatusEvent getNextSubEvent(Iterator<ChildStatusEvent> iter)
            throws IndexingException, RetriableIndexingException {
        try {
            return iter.next();
        } catch (IndexingExceptionUncheckedWrapper e) {
            throw e.getIndexingException();
        } catch (RetriableIndexingExceptionUncheckedWrapper e) {
            throw e.getIndexingException();
        }
    }
    
    private void handleException(
            final String error,
            final StatusEventWithId event,
            final IndexingException exception)
            throws FatalIndexingException, InterruptedException {
        try {
            if (event.isParentId()) { // child event
                retrier.retryCons(s -> s.store((ChildStatusEvent) event,
                                exception.getErrorType().toString(), exception),
                        storage, event);
            } else {
                retrier.retryCons(s -> s.setProcessingState(
                                event.getID(),
                                StatusEventProcessingState.PROC,
                                exception.getErrorType().toString(),
                                exception),
                        storage, event);
            }
        } catch (FatalIndexingException e) {
            throw e;
        } catch (IndexingException e) { // untestable
            throw new RuntimeException(
                    "non-fatal indexing exceptions should not be thrown here", e);
        }
        final String msg = error + String.format(" for event %s %s%s",
                event.getEvent().getEventType(),
                event.isParentId() ? "with parent ID " : "",
                        event.getID().getId());
        logError(msg, exception);
        if (exception instanceof FatalIndexingException) {
            throw (FatalIndexingException) exception;
        }
    }

    private String toLogString(final Optional<StorageObjectType> type) {
        if (!type.isPresent()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(type.get().getStorageCode());
        sb.append(":");
        sb.append(type.get().getType());
        if (type.get().getVersion().isPresent()) {
            sb.append("-");
            sb.append(type.get().getVersion().get());
        }
        sb.append(", ");
        return sb.toString();
    }

    private EventHandler getEventHandler(final StoredStatusEvent ev)
            throws UnprocessableEventIndexingException {
        return getEventHandler(ev.getEvent().getStorageCode());
    }
    
    private EventHandler getEventHandler(final GUID guid)
            throws UnprocessableEventIndexingException {
        return getEventHandler(guid.getStorageCode());
    }
    
    private EventHandler getEventHandler(final String storageCode)
            throws UnprocessableEventIndexingException {
        if (!eventHandlers.containsKey(storageCode)) {
            throw new UnprocessableEventIndexingException(ErrorType.OTHER, String.format(
                    "No event handler for storage code %s is registered", storageCode));
        }
        return eventHandlers.get(storageCode);
    }

    private void processEvent(final StatusEvent ev)
            throws IndexingException, InterruptedException, RetriableIndexingException {

        // update event to reflect the latest state of object for which the specified StatusEvent is an event for
        EventHandler handler = getEventHandler(ev.getStorageCode());
        StatusEvent updatedEvent = handler.updateObjectEvent(ev);

        try {
            switch (updatedEvent.getEventType()) {
            case NEW_VERSION:
                GUID pguid = updatedEvent.toGUID();
                boolean indexed = indexingStorage.hasParentId(
                        updatedEvent.getStorageObjectType().get().getType(), updatedEvent.toGUID());
                boolean overwriteExistingData = updatedEvent.isOverwriteExistingData().or(false);
                if (indexed && !overwriteExistingData) {
                    logger.logInfo("[Indexer]   skipping " + pguid +
                            " creation (already indexed and overwriteExistingData flag is set to false)");
                    // TODO: we should fix public access for all sub-objects too (maybe already works. Anyway, ensure all subobjects are set correctly as well as the parent)
                    if (updatedEvent.isPublic().get()) {
                        publish(pguid);
                    } else {
                        unpublish(pguid);
                    }
                } else {
                    indexObject(pguid, updatedEvent.getStorageObjectType().get(), updatedEvent.getTimestamp(),
                            updatedEvent.isPublic().get(), null, new LinkedList<>());
                }
                break;
            // currently unused
//            case DELETED:
//                unshare(ev.toGUID(), ev.getAccessGroupId().get());
//                break;
            case DELETE_ALL_VERSIONS:
                deleteAllVersions(updatedEvent.toGUID());
                break;
            case UNDELETE_ALL_VERSIONS:
                undeleteAllVersions(updatedEvent.toGUID());
                break;
                //TODO DP reenable if we support DPs
//            case SHARED:
//                share(ev.toGUID(), ev.getTargetAccessGroupId());
//                break;
                //TODO DP reenable if we support DPs
//            case UNSHARED:
//                unshare(ev.toGUID(), ev.getTargetAccessGroupId());
//                break;
            case RENAME_ALL_VERSIONS:
                renameAllVersions(updatedEvent.toGUID(), updatedEvent.getNewName().get());
                break;
            case PUBLISH_ALL_VERSIONS:
                publishAllVersions(updatedEvent.toGUID());
                break;
            case UNPUBLISH_ALL_VERSIONS:
                unpublishAllVersions(updatedEvent.toGUID());
                break;
            default:
                throw new UnprocessableEventIndexingException(
                        ErrorType.OTHER, "Unsupported event type: " + ev.getEventType());
            }
        } catch (IOException e) {
            // may want to make IndexingStorage throw more specific exceptions, but this will work
            // for now. Need to look more carefully at the code before that happens.
            throw new RetriableIndexingException(ErrorType.OTHER, e.getMessage(), e);
        } catch (IndexingConflictException e) {
            throw new RetriableIndexingException(ErrorType.INDEXING_CONFLICT, e.getMessage(), e);
        }
    }

    /** Index the object with the specified guid.
     *
     * @param guid an id that uniquely identifies the object that is to be indexed.
     * @param storageObjectType type of object that is to be indexed.
     * @param timestamp time at which this object was updated.
     * @param isPublic object access level (true if public, else false).
     * @param indexLookup
     * @param objectRefPath
     * @throws IndexingException
     * @throws InterruptedException
     * @throws RetriableIndexingException
     */
    private void indexObject(
            final GUID guid,
            final StorageObjectType storageObjectType,
            final Instant timestamp,
            final boolean isPublic,
            ObjectLookupProvider indexLookup,
            final List<GUID> objectRefPath) 
            throws IndexingException, InterruptedException, RetriableIndexingException {
        /* it'd be nice to be able to log the event ID with retry logging in sub methods,
         * but this method handles calls from recursive indexing where the event id isn't
         * available. Changing that would require passing the event all the way through the
         * parsing code. Not sure if it's worth the trouble since the event ID *is* logged if
         * retrying doesn't fix the problem.
         * 
         * Although, since this is single threaded, could make the event an instance variable,
         * but that seems icky and we may want to make the workers multithreaded in the future.
         * ThreadLocal?
         * https://sites.google.com/site/unclebobconsultingllc/thread-local-a-convenient-abomination
         * 
         */
        long t1 = System.currentTimeMillis();
        final File tempFile;
        try {
            FileUtil.getOrCreateSubDir(rootTempDir, guid.getStorageCode());
            tempFile = File.createTempFile("ws_srv_response_", ".json");
        } catch (IOException e) {
            throw new FatalRetriableIndexingException(ErrorType.OTHER, e.getMessage(), e);
        }
        if (indexLookup == null) {
            indexLookup = new MOPLookupProvider();
        }
        try {
            // make a copy to avoid mutating the caller's path
            final LinkedList<GUID> newRefPath = new LinkedList<>(objectRefPath);
            newRefPath.add(guid);
            final EventHandler handler = getEventHandler(guid);
            final SourceData obj = handler.load(newRefPath, tempFile.toPath());
            long loadTime = System.currentTimeMillis() - t1;
            logger.logInfo("[Indexer]   " + guid + ", loading time: " + loadTime + " ms.");
            logger.timeStat(guid, loadTime, 0, 0);
            final List<ObjectTypeParsingRules> parsingRules = new ArrayList<>( 
                    typeStorage.listObjectTypeParsingRules(storageObjectType));
            Collections.sort(parsingRules, new ParsingRulesSubtypeFirstComparator());
            for (final ObjectTypeParsingRules rule : parsingRules) {
                final long t2 = System.currentTimeMillis();
                final ParseObjectsRet parsedRet = parseObjects(guid, indexLookup,
                        newRefPath, obj, rule);
                long parsingTime = System.currentTimeMillis() - t2;
                logger.logInfo(String.format("[Indexer]   Parsed %s %s in %s ms.",
                        parsedRet.guidToObj.size(), toVerRep(rule.getGlobalObjectType()),
                        parsingTime));
                long t3 = System.currentTimeMillis();
                indexObjectInStorage(guid, timestamp, isPublic, obj, rule,
                        parsedRet.guidToObj, parsedRet.parentJson);
                long indexTime = System.currentTimeMillis() - t3;
                logger.logInfo("[Indexer]   " + toVerRep(rule.getGlobalObjectType()) +
                        ", indexing time: " + indexTime + " ms.");
                logger.timeStat(guid, 0, parsingTime, indexTime);
            }
        } finally {
            tempFile.delete();
        }
    }

    private String toVerRep(final SearchObjectType globalObjectType) {
        return globalObjectType.getType() + "_" + globalObjectType.getVersion();
    }

    private void indexObjectInStorage(
            final GUID guid,
            final Instant timestamp,
            final boolean isPublic,
            final SourceData obj,
            final ObjectTypeParsingRules rule,
            final Map<GUID, ParsedObject> guidToObj,
            final String parentJson)
            throws InterruptedException, IndexingException {
        final List<?> input = Arrays.asList(rule, obj, timestamp, parentJson, guid, guidToObj,
                isPublic);
        retrier.retryCons(i -> indexObjectInStorage(i), input, null);
    }

    private void indexObjectInStorage(final List<?> input) throws RetriableIndexingException {
        final ObjectTypeParsingRules rule = (ObjectTypeParsingRules) input.get(0);
        final SourceData obj = (SourceData) input.get(1);
        final Instant timestamp = (Instant) input.get(2);
        final String parentJson = (String) input.get(3);
        final GUID guid = (GUID) input.get(4);
        @SuppressWarnings("unchecked")
        final Map<GUID, ParsedObject> guidToObj = (Map<GUID, ParsedObject>) input.get(5);
        final Boolean isPublic = (Boolean) input.get(6);
        
        try {
            indexingStorage.indexObjects(
                    rule, obj, timestamp, parentJson, guid, guidToObj, isPublic);
        } catch (IndexingConflictException e) {
            throw new RetriableIndexingException(ErrorType.INDEXING_CONFLICT, e.getMessage(), e);
        } catch (IOException e) {
            throw new FatalRetriableIndexingException(ErrorType.OTHER, e.getMessage(), e);
        }
    }

    private class ParseObjectsRet {
        public final String parentJson;
        public final Map<GUID, ParsedObject> guidToObj;
        
        private ParseObjectsRet(final String parentJson, final Map<GUID, ParsedObject> guidToObj) {
            this.parentJson = parentJson;
            this.guidToObj = guidToObj;
        }
    }
    
    private ParseObjectsRet parseObjects(
            final GUID guid,
            final ObjectLookupProvider indexLookup,
            final LinkedList<GUID> newRefPath,
            final SourceData obj,
            final ObjectTypeParsingRules rule)
            throws IndexingException, InterruptedException {
        final List<?> inputs = Arrays.asList(guid, indexLookup, newRefPath, obj, rule);
        return retrier.retryFunc(i -> parseObjects(i), inputs, null);
    }
    
    private ParseObjectsRet parseObjects(final List<?> inputs)
            throws IndexingException, FatalRetriableIndexingException, InterruptedException {
        // should really wrap these in a class, but meh for now
        final GUID guid = (GUID) inputs.get(0);
        final ObjectLookupProvider indexLookup = (ObjectLookupProvider) inputs.get(1);
        @SuppressWarnings("unchecked")
        final List<GUID> newRefPath = (List<GUID>) inputs.get(2);
        final SourceData obj = (SourceData) inputs.get(3);
        final ObjectTypeParsingRules rule = (ObjectTypeParsingRules) inputs.get(4);

        final Map<GUID, ParsedObject> guidToObj = new HashMap<>();
        final String parentJson;
        try {
            try (JsonParser jts = obj.getData().getPlacedStream()) {
                parentJson = ObjectParser.extractParentFragment(rule, jts);
            }
            final Map<GUID, String> guidToJson = ObjectParser.parseSubObjects(obj, guid, rule);
            if (guidToJson.size() > maxObjectsPerLoad) {
                throw new UnprocessableEventIndexingException(ErrorType.SUBOBJECT_COUNT,
                        String.format("Object %s has %s subobjects, exceeding the limit of %s",
                        guid, guidToJson.size(), maxObjectsPerLoad));
            }
            for (final GUID subGuid : guidToJson.keySet()) {
                final String json = guidToJson.get(subGuid);
                guidToObj.put(subGuid, KeywordParser.extractKeywords(
                        subGuid, rule.getGlobalObjectType(), json, parentJson,
                        rule.getIndexingRules(), indexLookup, newRefPath));
            }
            /* any errors here are due to file IO or parse exceptions.
             * Parse exceptions are def not retriable
             * File IO problems are generally going to mean something is very wrong
             * (like bad disk), since the file should already exist at this point.
             */
        } catch (GUIDTooLongException ex) {
            throw new UnprocessableEventIndexingException(ErrorType.OTHER, ex.getMessage());
        } catch (GUIDNotFoundException e) {
            throw new UnprocessableEventIndexingException(
                    ErrorType.GUID_NOT_FOUND, e.getMessage(), e);
        } catch (ContigLocationException e) {
            throw new UnprocessableEventIndexingException(
                    ErrorType.LOCATION_ERROR, e.getMessage(), e);
        } catch (ObjectParseException e) {
            throw new UnprocessableEventIndexingException(ErrorType.OTHER, e.getMessage(), e);
        } catch (IOException e) {
            throw new FatalRetriableIndexingException(ErrorType.OTHER, e.getMessage(), e);
        }
        return new ParseObjectsRet(parentJson, guidToObj);
    }
    
//    private void share(GUID guid, int accessGroupId) throws IOException {
//        indexingStorage.shareObjects(new LinkedHashSet<>(Arrays.asList(guid)), accessGroupId, 
//                false);
//    }
    
    private void undeleteAllVersions(final GUID guid)
            throws IOException, IndexingConflictException {
        indexingStorage.undeleteAllVersions(guid);
    }

//    private void unshare(GUID guid, int accessGroupId) throws IOException {
//        indexingStorage.unshareObjects(new LinkedHashSet<>(Arrays.asList(guid)), accessGroupId);
//    }

    private void deleteAllVersions(final GUID guid)
            throws IOException, IndexingConflictException {
        indexingStorage.deleteAllVersions(guid);
    }

    private void publish(final GUID guid) throws IOException, IndexingConflictException {
        indexingStorage.publishObjects(new LinkedHashSet<>(Arrays.asList(guid)));
    }
    
    private void publishAllVersions(final GUID guid)
            throws IOException, IndexingConflictException {
        indexingStorage.publishAllVersions(guid);
        //TODO DP need to handle objects in datapalette
    }

    private void unpublish(final GUID guid) throws IOException, IndexingConflictException {
        indexingStorage.unpublishObjects(new LinkedHashSet<>(Arrays.asList(guid)));
    }
    
    private void unpublishAllVersions(final GUID guid)
            throws IOException, IndexingConflictException {
        indexingStorage.unpublishAllVersions(guid);
        //TODO DP need to handle objects in datapalette
    }
    
    private void renameAllVersions(final GUID guid, final String newName)
            throws IOException, IndexingConflictException {
        indexingStorage.setNameOnAllObjectVersions(guid, newName);
    }

    /** A lookup provider
     *
     */
    private class MOPLookupProvider implements ObjectLookupProvider {
        // storage code -> full ref path -> resolved guid
        private Map<String, Map<String, GUID>> refResolvingCache = new LinkedHashMap<>();
        private Map<GUID, ObjectData> objLookupCache = new LinkedHashMap<>();
        private Map<GUID, SearchObjectType> guidToTypeCache = new LinkedHashMap<>();
        
        @Override
        public Set<GUID> resolveRefs(List<GUID> callerRefPath, Set<GUID> refs)
                throws IndexingException, InterruptedException {
            /* the caller ref path 1) ensures that the object refs are valid when checked against
             * the source, and 2) allows getting deleted objects with incoming references 
             * in the case of the workspace
             */
            
            // there may be a way to cache more of this info and call the workspace less
            // by checking the ref against the refs in the parent object.
            // doing it the dumb way for now.
            final EventHandler eh = getEventHandler(callerRefPath.get(0));
            final String storageCode = eh.getStorageCode();
            if (!refResolvingCache.containsKey(storageCode)) {
                refResolvingCache.put(storageCode, new HashMap<>());
            }
            final Map<GUID, String> refToRefPath = eh.buildReferencePaths(callerRefPath, refs);
            Set<GUID> ret = new LinkedHashSet<>();
            Set<GUID> refsToResolve = new LinkedHashSet<>();
            for (final GUID ref : refs) {
                final String refpath = refToRefPath.get(ref);
                if (refResolvingCache.get(storageCode).containsKey(refpath)) {
                    ret.add(refResolvingCache.get(storageCode).get(refpath));
                } else {
                    refsToResolve.add(ref);
                }
            }
            if (refsToResolve.size() > 0) {
                final Set<ResolvedReference> resrefs =
                        resolveReferences(eh, callerRefPath, refsToResolve);
                for (final ResolvedReference rr: resrefs) {
                    final GUID guid = rr.getResolvedReference();
                    final boolean indexed = retrier.retryFunc(
                            g -> checkParentGuidExists(g), guid, null);
                    if (!indexed) {
                        indexObjectWrapperFn(guid, rr.getType(), rr.getTimestamp(), false,
                                this, callerRefPath);
                    }
                    ret.add(guid);
                    refResolvingCache.get(storageCode)
                            .put(refToRefPath.get(rr.getReference()), guid);
                }
            }
            return ret;
        }
        
        private boolean checkParentGuidExists(final GUID guid) throws RetriableIndexingException {
            try {
                return indexingStorage.checkParentGuidsExist(new HashSet<>(Arrays.asList(guid)))
                        .get(guid);
            } catch (IOException e) {
                throw new RetriableIndexingException(ErrorType.OTHER, e.getMessage(), e);
            }
        }
        
        private Set<ResolvedReference> resolveReferences(
                final EventHandler eh,
                final List<GUID> callerRefPath,
                final Set<GUID> refsToResolve)
                throws IndexingException, InterruptedException {
            final List<Object> input = Arrays.asList(eh, callerRefPath, refsToResolve);
            return retrier.retryFunc(i -> resolveReferences(i), input, null);
        }
        
        private Set<ResolvedReference> resolveReferences(final List<Object> input)
                throws IndexingException, RetriableIndexingException {
            final EventHandler eh = (EventHandler) input.get(0);
            @SuppressWarnings("unchecked")
            final List<GUID> callerRefPath = (List<GUID>) input.get(1);
            @SuppressWarnings("unchecked")
            final Set<GUID> refsToResolve = (Set<GUID>) input.get(2);

            return eh.resolveReferences(callerRefPath, refsToResolve);
        }
        
        private void indexObjectWrapperFn(
                final GUID guid,
                final StorageObjectType storageObjectType,
                final Instant timestamp,
                final boolean isPublic,
                final ObjectLookupProvider indexLookup,
                final List<GUID> objectRefPath) 
                throws IndexingException, InterruptedException {
            final List<Object> input = Arrays.asList(guid, storageObjectType, timestamp, isPublic,
                    indexLookup, objectRefPath);
            retrier.retryCons(i -> indexObjectWrapperFn(i), input, null);
        }

        private void indexObjectWrapperFn(final List<Object> input)
                throws IndexingException, InterruptedException, RetriableIndexingException {
            final GUID guid = (GUID) input.get(0);
            final StorageObjectType storageObjectType = (StorageObjectType) input.get(1);
            final Instant timestamp = (Instant) input.get(2);
            final boolean isPublic = (boolean) input.get(3);
            final ObjectLookupProvider indexLookup = (ObjectLookupProvider) input.get(4);
            @SuppressWarnings("unchecked")
            final List<GUID> objectRefPath = (List<GUID>) input.get(5);
            
            indexObject(guid, storageObjectType, timestamp, isPublic, indexLookup, objectRefPath);
        }

        @Override
        public Map<GUID, ObjectData> lookupObjectsByGuid(final Set<GUID> guids)
                throws InterruptedException, IndexingException {
            Map<GUID, ObjectData> ret = new LinkedHashMap<>();
            Set<GUID> guidsToLoad = new LinkedHashSet<>();
            for (GUID guid : guids) {
                if (objLookupCache.containsKey(guid)) {
                    ret.put(guid, objLookupCache.get(guid));
                } else {
                    guidsToLoad.add(guid);
                }
            }
            if (guidsToLoad.size() > 0) {
                final List<ObjectData> objList =
                        retrier.retryFunc(g -> getObjectsByIds(g), guidsToLoad, null);
                // for some reason I don't understand a stream implementation would throw
                // duplicate key errors on the ObjectData, which is the value
                final Map<GUID, ObjectData> loaded = new HashMap<>();
                for (final ObjectData od: objList) {
                    loaded.put(od.getGUID(), od);
                }
                objLookupCache.putAll(loaded);
                ret.putAll(loaded);
            }
            return ret;
        }
        
        private List<ObjectData> getObjectsByIds(final Set<GUID> guids)
                throws RetriableIndexingException {
            kbasesearchengine.search.PostProcessing pp = 
                    new kbasesearchengine.search.PostProcessing();
            pp.objectData = false;
            pp.objectKeys = true;
            try {
                return indexingStorage.getObjectsByIds(guids, pp);
            } catch (IOException e) {
                throw new RetriableIndexingException(ErrorType.OTHER, e.getMessage(), e);
            }
        }
        
        @Override
        public ObjectTypeParsingRules getTypeDescriptor(final SearchObjectType type)
                throws NoSuchTypeException {
            return typeStorage.getObjectTypeParsingRules(type);
        }
        
        @Override
        public Map<GUID, SearchObjectType> getTypesForGuids(Set<GUID> guids)
                throws InterruptedException, IndexingException {
            Map<GUID, SearchObjectType> ret = new LinkedHashMap<>();
            Set<GUID> guidsToLoad = new LinkedHashSet<>();
            for (GUID guid : guids) {
                if (guidToTypeCache.containsKey(guid)) {
                    ret.put(guid, guidToTypeCache.get(guid));
                } else {
                    guidsToLoad.add(guid);
                }
            }
            if (guidsToLoad.size() > 0) {
                final List<ObjectData> data =
                        retrier.retryFunc(g -> getObjectsByIds(g), guidsToLoad, null);
                // for some reason I don't understand a stream implementation would throw
                // duplicate key errors on the od.getType(), which is the value
                final Map<GUID, SearchObjectType> loaded = new HashMap<>();
                for (final ObjectData od: data) {
                    loaded.put(od.getGUID(), od.getType());
                }
                guidToTypeCache.putAll(loaded);
                ret.putAll(loaded);
            }
            return ret;
        }
    }
}
