package com.whisppa.droidfluxlib.impl;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.whisppa.droidfluxlib.Callback;
import com.whisppa.droidfluxlib.DispatchExceptionListener;
import com.whisppa.droidfluxlib.Dispatcher;
import com.whisppa.droidfluxlib.Payload;
import com.whisppa.droidfluxlib.Store;
import com.whisppa.droidfluxlib.utils.CollectionUtil;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by user on 5/5/2015.
 * I might be overly compensating for concurrency, but thread safety is not my forte
 */
public class DispatcherImpl implements Dispatcher {

    private static final String TAG = "DroidFlux:Dispatcher";

    protected final ConcurrentHashMap<String, Store> mStores = new ConcurrentHashMap<String, Store>();
//    private final DispatchThread mDispatchThread;
    protected AtomicBoolean mIsDispatching = new AtomicBoolean(false);
    protected String mCurrentActionType = null;
    protected java.util.Collection mWaitingToDispatch;

    public DispatcherImpl() {
//        mDispatchThread = new DispatchThread();
//        mDispatchThread.start();
    }

    @Override
    public void addStore(@NonNull String name, @NonNull Store store) {
        mStores.put(name, store);
    }

    public synchronized void dispatch(@NonNull Payload payload) {
        dispatch(payload, null);
    }

    public synchronized void dispatch(@NonNull final Payload payload, DispatchExceptionListener dispatchExceptionListener) {
        if (payload == null || TextUtils.isEmpty(payload.Type)) {
            throw new IllegalArgumentException("Can only dispatch actions with a valid 'Type' property");
        }

        if (isDispatching()) {
            throw new RuntimeException("Cannot dispatch an action ('" + payload.Type + "') while another action ('" + mCurrentActionType + "') is being dispatched");
        }

        String[] names = mStores.keySet().toArray(new String[0]);
        for(int i=0; i < names.length; i++) {
            Store store = mStores.get(names[i]);
            store.reset();
        }

        mCurrentActionType = payload.Type;
        mWaitingToDispatch = new HashSet(mStores.keySet());

        //set a default exception listener
        if(dispatchExceptionListener == null) {
            dispatchExceptionListener = new DispatchExceptionListener() {
                @Override
                public void onException(Exception e) {
                    Log.e(TAG, "A dispatch exception occurred", e);
                }
            };
        }

        mIsDispatching.set(true);

//        Message msg = Message.obtain();
//        msg.obj =  new DispatchArgs(payload, dispatchExceptionListener);
//        mDispatchThread.Handler.sendMessage(msg);

        try {
            Log.i("DroidFlux:Dispatcher", String.format("[STARTED] dispatch of action [%s]", payload.Type));
            doDispatchLoop(payload);
            Log.i("DroidFlux:Dispatcher", String.format("[COMPLETED] dispatch of action [%s]", payload.Type));
        }
        catch (Exception e) {
            Log.e("DroidFlux:Dispatcher", String.format("[FAILED] dispatch of action [%s]", payload.Type), e);
            dispatchExceptionListener.onException(e);
        }
        finally {
            mCurrentActionType = null;
            mIsDispatching.set(false);
        }
    }

    private synchronized void doDispatchLoop(Payload payload) throws Exception {

//        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
//            throw new Exception("Loop should not be run from the UI Thread");
//        }

        Store dispatch;
        Boolean canBeDispatchedTo = false;
        Boolean wasHandled = false;
        List<String> removeFromDispatchQueue = new ArrayList<>();
        List<String> dispatchedThisLoop = new ArrayList<>();

        Iterator<String> it = mWaitingToDispatch.iterator();
        while(it.hasNext()) {
            String key = it.next();

            dispatch = mStores.get(key);
            canBeDispatchedTo = (dispatch.getWaitingOnList().size() == 0) || (CollectionUtil.intersection(dispatch.getWaitingOnList(), new ArrayList<String>(mWaitingToDispatch)).size() == 0);

            if (canBeDispatchedTo) {
                if (dispatch.getWaitCallback() != null) {
                    Callback fn = dispatch.getWaitCallback();
                    dispatch.reset();
                    dispatch.setResolved(true);
                    fn.call();
                    wasHandled = true;
                }
                else {
                    dispatch.setResolved(true);
                    boolean handled = mStores.get(key).handleAction(payload);
                    if (handled) {
                        wasHandled = true;
                    }
                }

                dispatchedThisLoop.add(key);
                if (dispatch.isResolved()) {
                    removeFromDispatchQueue.add(key);
                }
            }

        }

        if (mWaitingToDispatch.size() > 0 && dispatchedThisLoop.size() == 0) {
            String storesWithCircularWaits = CollectionUtil.implode(mWaitingToDispatch.iterator());
            throw new Exception("Indirect circular wait detected among: " + storesWithCircularWaits);
        }

        for(int i = 0; i < removeFromDispatchQueue.size(); i++)
            mWaitingToDispatch.remove(removeFromDispatchQueue.get(i));

        if (mWaitingToDispatch.size() > 0) {
            this.doDispatchLoop(payload);
        }

        if (!wasHandled) {
            Log.d(TAG, "An action of type " + payload.Type + " was dispatched, but no store handled it");
        }
    }

    public void waitFor(String waitingStoreName, Set<String> storeNames, Callback callback) throws Exception {
        if (!isDispatching()) {
            throw new Exception("Cannot wait unless an action is being dispatched");
        }

        if (storeNames.contains(waitingStoreName)) {
            throw new Exception("A store cannot wait on itself");
        }

        Store dispatch = mStores.get(waitingStoreName);

        if (dispatch.getWaitingOnList().size() > 0) {
            throw new Exception(waitingStoreName + " is already waiting on stores");
        }

        Iterator<String> it = storeNames.iterator();
        while (it.hasNext()){
            String storeName = it.next();

            if (!mStores.containsKey(storeName)) {
                throw new Exception("Cannot wait for non-existent store " + storeName);
            }

            Store storeDispatch = mStores.get(storeName);
            if (storeDispatch.getWaitingOnList().contains(waitingStoreName)) {
                throw new Exception("Circular wait detected between " + waitingStoreName + " and " + storeName);
            }
        }

        dispatch.reset();

        dispatch.setWaitCallback(callback);
        dispatch.addToWaitingOnList(storeNames);
    }

    @Override
    public boolean isDispatching() {
        return mIsDispatching.get();
    }

//    class DispatchThread extends Thread {
//
//        public Handler Handler;
//        @Override
//        public void run(){
//            Looper.prepare();
//
//            Handler = new DispatchHandler(new WeakReference<DispatcherImpl>(DispatcherImpl.this));
//
//            Looper.loop();
//        }
//    }
    
//    static class DispatchHandler extends Handler {
//        private final WeakReference<DispatcherImpl> mDispatcher;
//
//        public DispatchHandler(WeakReference<DispatcherImpl> dispatcher) {
//            mDispatcher = dispatcher;
//        }
//
//        public void handleMessage(Message msg) {
//            DispatchArgs args = (DispatchArgs) msg.obj;
//
//            try {
//                mDispatcher.get().doDispatchLoop(args.Payload);
//            }
//            catch (Exception e) {
//                args.Listener.onException(e);
//            }
//            finally {
//                mDispatcher.get().mCurrentActionType = null;
//                mDispatcher.get().mIsDispatching.set(false);
//            }
//        }
//    }

//    class DispatchArgs {
//        Payload Payload;
//        DispatchExceptionListener Listener;
//
//        public DispatchArgs(Payload payload, DispatchExceptionListener dispatchExceptionListener) {
//            Payload = payload;
//            Listener = dispatchExceptionListener;
//        }
//    }
}
