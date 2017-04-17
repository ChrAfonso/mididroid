package com.xexano.mididroid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.media.midi.MidiOutputPort;
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
	private View main;

	// midi
	MidiManager midiManager;
	MidiDeviceInfo[] foundDevices;
	MidiDevice activeDevice;
	MidiInputPort sendPort;
	MidiOutputPort outputPort;

	int channel = 0; // TODO make configurable
	// TODO make port configurable

	// finger tracking
	int maxNumFingers = 4; // 1 for register, 3 for valves
	int currentRegisterFinger = 0;

	int deltaX_CC = 11; // TODO make configurable - like CC2 for emulating breath controller. change to something else if using together with breath controller
	boolean usePressXForVelocity = false; // TODO make configurable
	int velocity = 64; // middle velo as default

	// midi note layout
	private int numRegisters = 9;
	private int startNote = 58; // middle Bb -- TODO make configurable
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

		main = findViewById(R.id.trumpet_view);
		main.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent e) {
//				System.out.println("Touch Event "+e.getAction()+" at "+e.getX()+", "+e.getY());

				if(activeDevice == null) {
					initMidi();
					return false;
				}

				int action = e.getAction() & MotionEvent.ACTION_MASK;
                int actionIndex = e.getActionIndex(); // TODO are other pointers relevant? is there one event per pointer move? or do several change in one event?
				int nPointers = e.getPointerCount();

				int i = actionIndex; // TEST Just process the current action's finger - except for move, where we process the register finger
				int id = e.getPointerId(i); // for now we don;t really need this, doesn't matter which finger presses what
				if(id >= maxNumFingers) return false;

				// NOTE: Currently, left half is used for register, right quarter for valves (middle 3/5 y)
				switch (action) {
					case MotionEvent.ACTION_DOWN:
					case MotionEvent.ACTION_POINTER_DOWN:
						if (e.getX(i) < main.getWidth() / 2) {
							//left
							currentRegisterFinger = id; // only last touched finger on register area counts

							if(usePressXForVelocity) {
								velocity = (int)(e.getX(i)/(main.getWidth() / 2)*127);
							} else {
								setExpression((int) (e.getX(i)/(main.getWidth() / 2)*127));
							}
							setRegisterForYp(1 - e.getY(i) / main.getHeight());
						} else if (e.getX(i) > main.getWidth() * 0.75) {
							//right
							float fraction = 1 - (e.getY(i) / main.getHeight());
							if (fraction < 0.4) {
								setValve(0, true);
							} else if (fraction < 0.6) {
								setValve(1, true);
							} else if (fraction < 0.8) {
								setValve(2, true);
							} else {
								// TEMP reset button
								resetMidi();
							}
						}
						break;

					case MotionEvent.ACTION_UP:
					case MotionEvent.ACTION_POINTER_UP: // for up, also check dead area between register and valve area
						if (e.getX(i) < main.getWidth() * 0.75 && currentRegisterFinger == id) {
							//left
							setRegister(-1);
						} else if (e.getX(i) > main.getWidth() * 0.75) {
							//right
							float fraction = 1 - (e.getY(i) / main.getHeight());
							if (fraction < 0.4) {
								setValve(0, false);
							} else if (fraction < 0.6) {
								setValve(1, false);
							} else if (fraction < 0.8) {
								setValve(2, false);
							}
						}
						break;

					case MotionEvent.ACTION_MOVE:
						// NOTE swipe only for registers, so process register finger, no matter which finger triggered the event (move events always triggered on first finger down)
						int regIndex = e.findPointerIndex(currentRegisterFinger);
						if(regIndex > -1 && regIndex < e.getPointerCount()) {
							if (e.getX(regIndex) < main.getWidth() / 2) {
								setRegisterForYp(1 - e.getY(regIndex) / main.getHeight());

								setExpression((int) (e.getX(regIndex)/(main.getWidth() / 2)*127));
							}
						}
						break;
				}

				return true;
			}
		});

//		initMidi(); // wait for first press to give chance to initialize after starting app
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
						System.out.println("ERROR: Could not open midi device!");
					} else {
						setActiveDevice(device);
					}
				}
			}, new Handler(Looper.getMainLooper()));
		}
	}

	private void resetMidi() {
		System.out.println("Trying to reset midi...");
		if(activeDevice != null) {
			try {
				if (sendPort != null) {
					sendPort.close();
					System.out.println("Closed port.");
				}
				activeDevice.close();
				System.out.println("Closed device.");
			} catch(IOException e) {
				System.out.println("resetMidi:"+e);
			}
			sendPort = null;
			activeDevice = null;
		}
	}

	public void setActiveDevice(MidiDevice device) {
		if(activeDevice != null) {
			try {
				activeDevice.close();
			} catch(IOException e) {
				System.out.println("setActiveDevice: "+e);
			}
		}

		System.out.println("Opening send device "+device.toString());
		activeDevice = device;
		sendPort = device.openInputPort(0);
		outputPort = device.openOutputPort(0);
	}

	// NOTE: perhaps misleading, can be mapped to other cc - rename or change functionality?
	private void setExpression(int value) {
		setCCValue(deltaX_CC, value);
	}

	private void setCCValue(int cc, int value) {
		// clamp to valid values
		value = Math.min(Math.max(value, 0), 127);
		cc = Math.min(Math.max(cc, 0), 119);

		byte[] bytes = {(byte)(0xB0 | channel), (byte)(cc), (byte)(value)};

		try {
			sendPort.send(bytes, 0, 3);
		} catch(IOException e) {
			System.out.println("setCCValue: "+e);
		}
	}

	private void setRegisterForYp(double yp) {
		int index = (int)Math.floor(yp*numRegisters);
		setRegister(index);
	}

	private void setRegister(int register) {
		if(this.register != register) {
			System.out.println("setRegister: "+this.register+" -> "+register);
			if (register == -1) {
				// NOTE OFF
				this.register = -1;
			} else if (register < numRegisters) {
				this.register = register;
			}

			updateMidi();
		}
	}
	
	private void setValve(int valve, boolean pressed) {
		// TODO range check
		int newValue = pressed ? 1 : 0;
		if(newValue != valves[valve]) {
			valves[valve] = newValue;
			updateMidi();
		}
	}

	// TODO param for note changed? When we add CCs
	private void updateMidi() {
		if(currentNote != null) {
			note_off(currentNote.number);
		}

		if(register > -1) {
			int midinote = startNote + registerOffsets[register] - (2*valves[0] + valves[1] + 3*valves[2]);
			note_on(midinote, velocity);
		}

		updateScreen();
	}
	
	private void note_off(int number) {
		byte[] bytes = {(byte)(0x80 + channel), (byte)currentNote.number, (byte)0};
		try {
			 System.out.println("Sending Note off: "+number);
//			 System.out.println("Bytes: "+bytes[0]+" "+bytes[1]+" "+bytes[2]);
			sendPort.send(bytes, 0, 3);
		} catch(IOException e) {
			System.out.println("note_off: "+e);
		}
		currentNote = null;
	}

	private void note_on(int number, int velocity) {
		currentNote = new Note(number, velocity);
		try {
			byte[] bytes = {(byte)(0x90 + channel), (byte)number, (byte)velocity};
			 System.out.println("Sending Note on: "+number+","+velocity);
//			 System.out.println("Bytes: "+bytes[0]+" "+bytes[1]+" "+bytes[2]);
			sendPort.send(bytes, 0, 3);
		} catch(IOException e) {
			System.out.println("note_on:"+e);
		}
	}

	private void updateScreen() {
		// TODO
//		Canvas canvas;
//		main.draw(canvas);
//
//		main.invalidate();
	}

	@Override
	public void onStart() {
		super.onStart();
	}

	@Override
	public void onStop() {
		super.onStop();

		resetMidi();
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
