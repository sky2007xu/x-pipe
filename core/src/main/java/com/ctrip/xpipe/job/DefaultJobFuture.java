package com.ctrip.xpipe.job;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.ctrip.xpipe.api.job.JobFuture;

/**
 * @author wenchao.meng
 *
 * Jun 26, 2016
 */
public class DefaultJobFuture<V> implements JobFuture<V>{
	
	private volatile Object result = null;
	
	private static final CauseHolder  CANCELLED_RESULT = new CauseHolder(new CancellationException());
	
	private static final String SUCCESS_NO_RESULT = "SUCCESS_NO_RESULT";
	
    private short waiters = 0;
	

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		
		if(isDone()){
			return false;
		}
		
		synchronized(this){
			if(isDone()){
				return false;
			}
			result = CANCELLED_RESULT;
            if (hasWaiters()) {
                notifyAll();
            }
		}
		return true;
	}

	@Override
	public boolean isCancelled() {
		return isDone() && result == CANCELLED_RESULT;
	}

	@Override
	public boolean isDone() {
		return result != null;
	}


	@Override
	public void sync() throws InterruptedException, ExecutionException {
		get();
	}

	
    @Override
    public V get() throws InterruptedException, ExecutionException {
    	
        await();

        Throwable cause = cause();
        if (cause == null) {
            return getNow();
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

	@SuppressWarnings("unchecked")
	private V getNow() {
		
		if(result instanceof CauseHolder){
			return null;
		}
		return (V)result;
	}

	@Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (await(timeout, unit)) {
            Throwable cause = cause();
            if (cause == null) {
                return getNow();
            }
            if (cause instanceof CancellationException) {
                throw (CancellationException) cause;
            }
            throw new ExecutionException(cause);
        }
        throw new TimeoutException();
    }


    private static final class CauseHolder {
        final Throwable cause;
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }


	@Override
	public boolean isSuccess() {
		
		return result != null &&(result == SUCCESS_NO_RESULT || !(result instanceof CauseHolder));
	}

	@Override
	public Throwable cause() {
		
		if(result instanceof CauseHolder){
			return ((CauseHolder)result).cause;
		}
		return null;
	}

	@Override
	public void setSuccess(V result) {
		
		if(isDone()){
			throw new IllegalStateException("already completed!" + result);
		}
		
		synchronized (this) {
			if(isDone()){
				throw new IllegalStateException("already completed!" + result);
			}
			
			if(result != null){
				this.result = result;
			}else{
				this.result = SUCCESS_NO_RESULT;
			}
            if (hasWaiters()) {
                notifyAll();
            }
		}
	}

	@Override
	public void setFailure(Throwable cause) {
		if(isDone()){
			throw new IllegalStateException("already completed!" + result);
		}
		
		synchronized (this) {
			if(isDone()){
				throw new IllegalStateException("already completed!" + result);
			}
			this.result = new CauseHolder(cause);
            if (hasWaiters()) {
                notifyAll();
            }
		}
	}

	@Override
	public Future<V> await() throws InterruptedException {
	        if (isDone()) {
	            return this;
	        }

	        if (Thread.interrupted()) {
	            throw new InterruptedException(toString());
	        }

	        synchronized (this) {
	            while (!isDone()) {
	                incWaiters();
	                try {
	                    wait();
	                } finally {
	                    decWaiters();
	                }
	            }
	        }
	        return this;
	}

	@Override
	public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
		return await0(unit.toNanos(timeout), true);
	}

    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        if (isDone()) {
            return true;
        }

        if (timeoutNanos <= 0) {
            return isDone();
        }

        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        long startTime = System.nanoTime();
        long waitTime = timeoutNanos;
        boolean interrupted = false;

        try {
            synchronized (this) {
                if (isDone()) {
                    return true;
                }

                if (waitTime <= 0) {
                    return isDone();
                }

                incWaiters();
                try {
                    for (;;) {
                        try {
                            wait(waitTime / 1000000, (int) (waitTime % 1000000));
                        } catch (InterruptedException e) {
                            if (interruptable) {
                                throw e;
                            } else {
                                interrupted = true;
                            }
                        }

                        if (isDone()) {
                            return true;
                        } else {
                            waitTime = timeoutNanos - (System.nanoTime() - startTime);
                            if (waitTime <= 0) {
                                return isDone();
                            }
                        }
                    }
                } finally {
                    decWaiters();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean hasWaiters() {
        return waiters > 0;
    }

    private void incWaiters() {
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        waiters ++;
    }

    private void decWaiters() {
        waiters --;
    }
}
