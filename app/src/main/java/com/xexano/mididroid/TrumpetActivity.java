package com.xexano.mididroid;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import android.media.midi.MidiManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;

import java.io.IOException;

public class TrumpetActivity extends Activity {

	private Context context;

	// midi
	int channel = 0; // TODO make configurable
	MidiManager midiManager;
	MidiInputPort sendPort;

	// midi note layout
	private int numRegisters = 9;
	private int startNote = 46; // middle Bb -- TODO make configurable
	private int[] registerOffsets = {0, 7, 12, 16, 19, 22, 24, 26, 28};
	
	// current state
	private int register = 0;
	private int[] valves = {0, 0, 0};
	private Note currentNote = null;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.trumpet);
		
		init();
	}

	private void init() {
		System.out.println("Initializing...");
		DisplayMetrics displaymetrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
//		int height = displaymetrics.heightPixels;
//		int width = displaymetrics.widthPixels;

		context = this;//getApplicationContext();

		final LinearLayout main = (LinearLayout) findViewById(R.id.trumpet_view);
//		final LinearLayout main = new LinearLayout(context);
        	main.setOnTouchListener(new View.OnTouchListener() {
	        	@Override
        		public boolean onTouch(View v, MotionEvent e) {
//				System.out.println("Touch at "+e.getX()+", "+e.getY());

				if(e.getAction() == MotionEvent.ACTION_DOWN) {
					if(e.getX() < main.getWidth()/2) {
						//left
						setRegisterForYp(e.getY()/main.getHeight());
					} else if(e.getX() > main.getWidth()/2) {
						//right
						float fraction = (e.getY()/main.getHeight());
						if(fraction < 0.33) {
							setValve(0, true);
						} else if(fraction < 0.66) {
							setValve(1, true);
						} else {
							setValve(2, true);
						}
					}
				}
				
				else if(e.getAction() == MotionEvent.ACTION_UP) {
					if(e.getX() < main.getWidth()/2) {
						//left
						setRegister(-1);
					} else if(e.getX() > main.getWidth()/2) {
						//right
						float fraction = (e.getY()/main.getHeight());
						if(fraction < 0.33) {
							setValve(0, false);
						} else if(fraction < 0.66) {
							setValve(1, false);
						} else {
							setValve(2, false);
						}
						
					}
				}

				else if(e.getAction() == MotionEvent.ACTION_MOVE) {
					// TODO swipe only for registers
					if(e.getX() < main.getWidth()/2) {
						setRegisterForYp(e.getY()/main.getHeight());
					}
				}
	        	
	        	return true;
			}
		});
		
		initMidi();
	}
	
	private int portIndex = 0;
	private void initMidi() {
		midiManager = (MidiManager)context.getSystemService(Context.MIDI_SERVICE);
		MidiDeviceInfo[] infos = midiManager.getDevices();
		for(MidiDeviceInfo info: infos) {
			int numInputs = info.getInputPortCount();
			midiManager.openDevice(info, new MidiManager.OnDeviceOpenedListener() {
				@Override
				public void onDeviceOpened(MidiDevice device) {
					if(device == null) {
						// ERROR
					} else {
						System.out.println("Opening send device "+device.toString());
						sendPort = device.openInputPort(portIndex++);
					}
				}
			}, new Handler(Looper.getMainLooper()));
		}
	}

	private void setRegisterForYp(double yp) {
		int index = (int)Math.floor(yp*numRegisters);
		setRegister(index);
	}

	private void setRegister(int register) {
		if(register == -1) {
			// NOTE OFF
			this.register = -1;
		} else if(register < numRegisters) {
			this.register = register;
		}

		updateMidi();
	}
	
	private void setValve(int valve, boolean pressed) {
		// TODO range check
		valves[valve] = pressed ? 1 : 0;
		updateMidi();
	}

	// TODO param for note changed? When we add CCs
	private void updateMidi() {
		if(currentNote != null) {
			note_off(currentNote.number);
		}

		if(register > -1) {
			int midinote = startNote + registerOffsets[register] - (2*valves[0] + valves[1] + 3*valves[2]);
			note_on(midinote, 64); // TODO velocity by Gyro?
		}
	}
	
	private void note_off(int number) {
		byte[] bytes = {(byte)(0x80 + channel), (byte)currentNote.number, (byte)0};
		try {
			 System.out.println("Sending Note off: "+number);
			 System.out.println("Bytes: "+bytes[0]+" "+bytes[1]+" "+bytes[2]);
			sendPort.send(bytes, 0, 0);
		} catch(IOException e) {
			System.out.println(e);
		}
		currentNote = null;
	}

	private void note_on(int number, int velocity) {
		currentNote = new Note(number, velocity);
		try {
			byte[] bytes = {(byte)(0x90 + channel), (byte)number, (byte)velocity};
			 System.out.println("Sending Note on: "+number+","+velocity);
			 System.out.println("Bytes: "+bytes[0]+" "+bytes[1]+" "+bytes[2]);
			sendPort.send(bytes, 0, 0);
		} catch(IOException e) {
			System.out.println(e);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
	}

	@Override
	public void onStop() {
		super.onStop();

		if(sendPort != null) {
			try {
				sendPort.close();
			} catch(IOException e) {
				System.out.println(e);
			}
		}
	}

	@Override
	public void onPause() {
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();
	}
}
