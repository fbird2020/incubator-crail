/*
 * Crail: A Multi-tiered Distributed Direct Access File System
 *
 * Author: Patrick Stuedi <stu@zurich.ibm.com>
 *
 * Copyright (C) 2016, IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.crail.memory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.ibm.crail.conf.CrailConstants;
import com.ibm.crail.utils.CrailUtils;
import com.ibm.crail.CrailBuffer;
import com.ibm.crail.CrailStatistics;

public class MappedBufferCache extends BufferCache {
	private static final Logger LOG = CrailUtils.getLogger();
	
	private String id;
	private String directory;
	private File dir;
	private long allocationCount;
	private long bufferCount;
	private long currentRegion;	
	
	public MappedBufferCache() throws IOException {
		super();
		
		id = "" + System.currentTimeMillis();
		directory = CrailUtils.getCacheDirectory(id);
		dir = new File(directory);
		if (!dir.exists()){
			dir.mkdirs();
		}
		for (File child : dir.listFiles()) {
			child.delete();
		}
		
		this.allocationCount = CrailConstants.CACHE_LIMIT / CrailConstants.REGION_SIZE;
		long _bufferSize = (long) CrailConstants.BUFFER_SIZE;
		this.bufferCount = CrailConstants.REGION_SIZE / _bufferSize;
		this.currentRegion = 0;
		LOG.info("buffer cache, allocationCount " + allocationCount + ", bufferCount " + bufferCount);
	}
	
	@Override
	public void close() {
		super.close();
		
		if (dir.exists()){
			for (File child : dir.listFiles()) {
				child.delete();
			}
			dir.delete();
		}
		LOG.info("mapped client cache closed");
	}
	
	public CrailBuffer allocateBuffer() throws IOException{
		return allocateRegion();
	}

	private CrailBuffer allocateRegion() throws IOException {
		if (currentRegion >= allocationCount){
			return null;
		}
		
		String path = directory + "/" + currentRegion++;
		RandomAccessFile randomFile = new RandomAccessFile(path, "rw");
		randomFile.setLength(CrailConstants.REGION_SIZE);
		FileChannel channel = randomFile.getChannel();
		MappedByteBuffer _mappedBuffer = channel.map(MapMode.READ_WRITE, 0,
				CrailConstants.REGION_SIZE);
		CrailBuffer mappedBuffer = OffHeapBuffer.wrap(_mappedBuffer);
		randomFile.close();
		channel.close();

		CrailBuffer firstBuffer = slice(mappedBuffer, 0);
		
		for (int j = 1; j < bufferCount; j++) {
			int position = j * CrailConstants.BUFFER_SIZE;
			CrailBuffer sliceBuffer = slice(mappedBuffer, position);
			this.putBufferInternal(sliceBuffer);
		}
		mappedBuffer.clear();
		
		return firstBuffer;
	}
	
	private CrailBuffer slice(CrailBuffer mappedBuffer, int position){
		int limit = position + CrailConstants.BUFFER_SIZE;
		mappedBuffer.clear();
		mappedBuffer.position(position);
		mappedBuffer.limit(limit);
		CrailBuffer buffer = mappedBuffer.slice();			
		return buffer;
	}
}
