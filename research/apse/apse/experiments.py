from .research import cp_fit,substrates,fatmax,fatmax_curve,sleep_timeline,forecast_load,joint_update,wbal,fitness_fatigue_calibrate

def evaluate(p):
    if not isinstance(p,dict) or set(p)!={'model','inputs'} or not isinstance(p['inputs'],dict): raise ValueError('model and inputs required')
    models={f.__name__:f for f in (cp_fit,substrates,fatmax,fatmax_curve,sleep_timeline,forecast_load,joint_update,wbal,fitness_fatigue_calibrate)}
    if p['model'] not in models: raise ValueError('unknown model')
    return models[p['model']](**p['inputs'])
