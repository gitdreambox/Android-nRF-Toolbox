/*
 * Copyright (c) 2015, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package no.nordicsemi.android.nrftoolbox.gizwits;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;

import java.io.UnsupportedEncodingException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

import no.nordicsemi.android.log.Logger;
import no.nordicsemi.android.nrftoolbox.R;
import no.nordicsemi.android.nrftoolbox.profile.BleManager;
import no.nordicsemi.android.nrftoolbox.parser.BodySensorLocationParser;
import no.nordicsemi.android.nrftoolbox.parser.HeartRateMeasurementParser;

/**
 * HRSManager class performs BluetoothGatt operations for connection, service discovery, enabling notification and reading characteristics. All operations required to connect to device with BLE HR
 * Service and reading heart rate values are performed here. HRSActivity implements HRSManagerCallbacks in order to receive callbacks of BluetoothGatt operations
 */
public class GizwitsManager extends BleManager<GizwitsManagerCallbacks> {
	public final static UUID G_SERVICE_UUID = UUID.fromString("0000FFB0-0000-1000-8000-00805f9b34fb");
	private static final UUID G_Write_CHARACTERISTIC_UUID = UUID.fromString("0000FFB1-0000-1000-8000-00805f9b34fb");
	private static final UUID G_Notify_CHARACTERISTIC_UUID = UUID.fromString("0000FFB2-0000-1000-8000-00805f9b34fb");

	private BluetoothGattCharacteristic mWriteCharacteristic, mNotifyCharacteristic;
	private byte[] mOutgoingBuffer;
	private int mBufferOffset;
	private static final int MAX_PACKET_SIZE = 20;

	private static GizwitsManager managerInstance = null;

	/**
	 * singleton implementation of HRSManager class
	 */
	public static synchronized GizwitsManager getInstance(final Context context) {
		if (managerInstance == null) {
			managerInstance = new GizwitsManager(context);
		}
		return managerInstance;
	}

	public GizwitsManager(final Context context) {
		super(context);
	}

	@Override
	protected BleManagerGattCallback getGattCallback() {
		return mGattCallback;
	}

	/**
	 * BluetoothGatt callbacks for connection/disconnection, service discovery, receiving notification, etc
	 */
	private final BleManagerGattCallback mGattCallback = new BleManagerGattCallback() {

		@Override
		protected Queue<Request> initGatt(final BluetoothGatt gatt) {
			final LinkedList<Request> requests = new LinkedList<>();
			requests.push(Request.newEnableNotificationsRequest(mNotifyCharacteristic));
			return requests;
		}

		@Override
		protected boolean isRequiredServiceSupported(final BluetoothGatt gatt) {
			final BluetoothGattService service = gatt.getService(G_SERVICE_UUID);
			if (service != null) {
				mWriteCharacteristic = service.getCharacteristic(G_Write_CHARACTERISTIC_UUID);
				mNotifyCharacteristic = service.getCharacteristic(G_Notify_CHARACTERISTIC_UUID);
			}

			boolean writeRequest = false;
			boolean writeCommand = false;
			if (mNotifyCharacteristic != null) {
				final int rxProperties = mNotifyCharacteristic.getProperties();
				writeRequest = (rxProperties & BluetoothGattCharacteristic.PROPERTY_WRITE) > 0;
				writeCommand = (rxProperties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) > 0;

				// Set the WRITE REQUEST type when the characteristic supports it. This will allow to send long write (also if the characteristic support it).
				// In case there is no WRITE REQUEST property, this manager will divide texts longer then 20 bytes into up to 20 bytes chunks.
				if (writeRequest)
					mNotifyCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
			}

			return mNotifyCharacteristic != null && mWriteCharacteristic != null && (writeRequest || writeCommand);
		}


		@Override
		protected void onDeviceDisconnected() {
			mWriteCharacteristic = null;
			mNotifyCharacteristic = null;
		}

		@Override
		public void onCharacteristicNotified(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
			final String data = characteristic.getStringValue(0);
			mCallbacks.onDataReceived(data);
		}

		@Override
		protected void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			// When the whole buffer has been sent
			final byte[] buffer = mOutgoingBuffer;
			if (mBufferOffset == buffer.length) {
				try {
					mCallbacks.onDataSent(new String(buffer, "UTF-8"));
				} catch (final UnsupportedEncodingException e) {
					// do nothing
				}
				mOutgoingBuffer = null;
			} else { // Otherwise...
				final int length = Math.min(buffer.length - mBufferOffset, MAX_PACKET_SIZE);
				final byte[] data = new byte[length]; // We send at most 20 bytes
				System.arraycopy(buffer, mBufferOffset, data, 0, length);
				mBufferOffset += length;
				mWriteCharacteristic.setValue(data);
				writeCharacteristic(mWriteCharacteristic);
			}
		}
	};
}
